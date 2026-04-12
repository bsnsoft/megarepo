import http from "k6/http";
import { check, fail } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const USERNAME = __ENV.USERNAME || "admin";
const PASSWORD = __ENV.PASSWORD || "admin123";

/**
 * Authenticate against MegaRepo and return a JWT token.
 * Fails the test iteration if login does not succeed.
 */
export function login(username, password) {
  const res = http.post(
    `${BASE_URL}/api/v1/security/auth/login`,
    JSON.stringify({
      username: username || USERNAME,
      password: password || PASSWORD,
    }),
    { headers: { "Content-Type": "application/json" } }
  );

  const ok = check(res, {
    "login status is 200": (r) => r.status === 200,
    "login returns token": (r) => {
      try {
        return JSON.parse(r.body).token !== undefined;
      } catch (_) {
        return false;
      }
    },
  });

  if (!ok) {
    fail(`Login failed (status=${res.status}): ${res.body}`);
  }

  return JSON.parse(res.body).token;
}

/**
 * Return standard headers including the Bearer token.
 */
export function authHeaders(token) {
  return {
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json",
  };
}

/**
 * Return headers for binary/file uploads with the Bearer token.
 */
export function uploadHeaders(token, contentType) {
  return {
    Authorization: `Bearer ${token}`,
    "Content-Type": contentType || "application/octet-stream",
  };
}

export { BASE_URL };
