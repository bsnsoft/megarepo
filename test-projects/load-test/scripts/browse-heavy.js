import http from "k6/http";
import { check, group, sleep } from "k6";
import { Trend, Rate } from "k6/metrics";
import { login, authHeaders, BASE_URL } from "./auth.js";

// Custom metrics
const browseLatency = new Trend("browse_latency", true);
const searchLatency = new Trend("search_latency", true);
const browseErrors = new Rate("browse_errors");

export const options = {
  scenarios: {
    browse: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "20s", target: 20 },
        { duration: "2m", target: 20 },
        { duration: "10s", target: 0 },
      ],
    },
  },
  thresholds: {
    browse_latency: ["p(95)<500"],
    search_latency: ["p(95)<800"],
    browse_errors: ["rate<0.01"],
  },
};

const SEARCH_KEYWORDS = [
  "core",
  "data",
  "web",
  "auth",
  "cache",
  "config",
  "event",
  "metrics",
  "utils",
  "lib",
  "spring",
  "commons",
  "jackson",
  "guava",
];

const FORMATS = ["maven", "npm", "pypi", "raw"];

function randomElement(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

export function setup() {
  const token = login();
  return { token };
}

export default function (data) {
  const headers = authHeaders(data.token);

  // Simulate a user browsing the UI

  group("list repositories", () => {
    const res = http.get(`${BASE_URL}/api/v1/repositories`, { headers });
    browseLatency.add(res.timings.duration);
    const ok = check(res, {
      "list repos returns 200": (r) => r.status === 200,
    });
    browseErrors.add(!ok);
  });

  sleep(0.3);

  group("search components", () => {
    const keyword = randomElement(SEARCH_KEYWORDS);
    const res = http.get(`${BASE_URL}/api/v1/search?q=${keyword}`, { headers });
    searchLatency.add(res.timings.duration);
    const ok = check(res, {
      "search returns 200": (r) => r.status === 200,
    });
    browseErrors.add(!ok);
  });

  sleep(0.2);

  group("search by format", () => {
    const format = randomElement(FORMATS);
    const res = http.get(`${BASE_URL}/api/v1/search?format=${format}`, { headers });
    searchLatency.add(res.timings.duration);
    const ok = check(res, {
      "search by format returns 200": (r) => r.status === 200,
    });
    browseErrors.add(!ok);
  });

  sleep(0.2);

  group("get system status", () => {
    const res = http.get(`${BASE_URL}/api/v1/status`, { headers });
    browseLatency.add(res.timings.duration);
    const ok = check(res, {
      "status returns 200": (r) => r.status === 200,
    });
    browseErrors.add(!ok);
  });

  sleep(0.2);

  group("list users", () => {
    const res = http.get(`${BASE_URL}/api/v1/security/users`, { headers });
    browseLatency.add(res.timings.duration);
    const ok = check(res, {
      "list users returns 200": (r) => r.status === 200,
    });
    browseErrors.add(!ok);
  });

  sleep(0.2);

  group("get license info", () => {
    const res = http.get(`${BASE_URL}/api/v1/system/license`, { headers });
    browseLatency.add(res.timings.duration);
    const ok = check(res, {
      "license returns 200": (r) => r.status === 200,
    });
    browseErrors.add(!ok);
  });

  sleep(0.3);
}
