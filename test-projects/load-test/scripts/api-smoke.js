import http from "k6/http";
import { check, group, sleep } from "k6";
import { Trend, Rate } from "k6/metrics";
import { login, authHeaders, BASE_URL } from "./auth.js";

// Custom metrics
const apiLatency = new Trend("api_latency", true);
const apiErrors = new Rate("api_errors");

export const options = {
  scenarios: {
    smoke: {
      executor: "shared-iterations",
      vus: 1,
      iterations: 1,
      maxDuration: "60s",
    },
  },
  thresholds: {
    api_latency: ["p(95)<500"],
    api_errors: ["rate<0.01"],
  },
};

export function setup() {
  const token = login();
  return { token };
}

export default function (data) {
  const headers = authHeaders(data.token);

  const endpoints = [
    { name: "GET /api/v1/repositories", url: `${BASE_URL}/api/v1/repositories` },
    { name: "GET /api/v1/security/users", url: `${BASE_URL}/api/v1/security/users` },
    { name: "GET /api/v1/search?q=test", url: `${BASE_URL}/api/v1/search?q=test` },
    { name: "GET /api/v1/status", url: `${BASE_URL}/api/v1/status` },
    { name: "GET /api/v1/system/license", url: `${BASE_URL}/api/v1/system/license` },
  ];

  for (const ep of endpoints) {
    group(ep.name, () => {
      const res = http.get(ep.url, { headers });

      apiLatency.add(res.timings.duration);

      const passed = check(res, {
        [`${ep.name} returns 200`]: (r) => r.status === 200,
        [`${ep.name} responds within 500ms`]: (r) => r.timings.duration < 500,
      });

      apiErrors.add(!passed);
    });

    sleep(0.2);
  }
}
