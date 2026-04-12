import http from "k6/http";
import { check, group, sleep } from "k6";
import { Trend, Rate, Counter } from "k6/metrics";
import { login, authHeaders, uploadHeaders, BASE_URL } from "./auth.js";

// --- Custom metrics ---
const uploadLatency = new Trend("upload_latency", true);
const downloadLatency = new Trend("download_latency", true);
const browseLatency = new Trend("browse_latency", true);
const searchLatency = new Trend("search_latency", true);
const errorRate = new Rate("error_rate");
const totalRequests = new Counter("total_requests");

const REPO_NAME = "loadtest-maven-hosted";

export const options = {
  scenarios: {
    full_load: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "2m", target: 50 },   // ramp up
        { duration: "5m", target: 50 },   // hold steady
        { duration: "1m", target: 0 },    // ramp down
      ],
    },
  },
  thresholds: {
    upload_latency: ["p(95)<1000", "p(99)<2000"],
    download_latency: ["p(95)<500", "p(99)<1000"],
    browse_latency: ["p(95)<500", "p(99)<1000"],
    search_latency: ["p(95)<800", "p(99)<1500"],
    error_rate: ["rate<0.01"],
    http_req_duration: ["p(95)<1000"],
  },
};

// --- Test data ---

const GROUP_IDS = [
  "com.loadtest.alpha",
  "com.loadtest.beta",
  "org.loadtest.gamma",
  "io.loadtest.delta",
  "net.loadtest.epsilon",
];

const ARTIFACT_IDS = [
  "core-lib", "data-utils", "web-framework", "auth-module",
  "cache-engine", "config-reader", "event-bus", "metrics-collector",
];

const VERSIONS = [
  "1.0.0", "1.0.1", "1.1.0", "1.2.0", "2.0.0", "2.1.0", "3.0.0-SNAPSHOT",
];

const SEARCH_KEYWORDS = [
  "core", "data", "web", "auth", "cache", "config", "event", "metrics",
  "utils", "lib", "framework", "engine",
];

function randomElement(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

function generatePom(groupId, artifactId, version) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>${groupId}</groupId>
  <artifactId>${artifactId}</artifactId>
  <version>${version}</version>
  <packaging>jar</packaging>
</project>`;
}

function generatePayload(size) {
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  let result = "";
  for (let i = 0; i < size; i++) {
    result += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return result;
}

function mavenPath(groupId, artifactId, version, filename) {
  return `${groupId.replace(/\./g, "/")}/${artifactId}/${version}/${filename}`;
}

// --- Setup ---

function ensureRepository(token) {
  const headers = authHeaders(token);
  const res = http.get(`${BASE_URL}/api/v1/repositories/${REPO_NAME}`, { headers });
  if (res.status === 200) return;

  http.post(
    `${BASE_URL}/api/v1/repositories`,
    JSON.stringify({
      name: REPO_NAME,
      format: "maven",
      type: "HOSTED",
      online: true,
      blobStoreName: "default",
      attributes: {},
    }),
    { headers }
  );
}

export function setup() {
  const token = login();
  ensureRepository(token);
  return { token };
}

// --- Scenario functions ---

function doUpload(token) {
  const groupId = randomElement(GROUP_IDS);
  const artifactId = randomElement(ARTIFACT_IDS);
  const version = randomElement(VERSIONS);
  const unique = `${version}-vu${__VU}-${__ITER}-${Date.now()}`;

  // Upload POM
  const pomPath = mavenPath(groupId, artifactId, unique, `${artifactId}-${unique}.pom`);
  const pomRes = http.put(
    `${BASE_URL}/repository/${REPO_NAME}/${pomPath}`,
    generatePom(groupId, artifactId, unique),
    { headers: uploadHeaders(token, "application/xml") }
  );
  uploadLatency.add(pomRes.timings.duration);
  totalRequests.add(1);
  const pomOk = check(pomRes, {
    "upload POM ok": (r) => r.status >= 200 && r.status < 300,
  });
  errorRate.add(!pomOk);

  // Upload JAR
  const jarPath = mavenPath(groupId, artifactId, unique, `${artifactId}-${unique}.jar`);
  const jarRes = http.put(
    `${BASE_URL}/repository/${REPO_NAME}/${jarPath}`,
    generatePayload(2048),
    { headers: uploadHeaders(token, "application/java-archive") }
  );
  uploadLatency.add(jarRes.timings.duration);
  totalRequests.add(1);
  const jarOk = check(jarRes, {
    "upload JAR ok": (r) => r.status >= 200 && r.status < 300,
  });
  errorRate.add(!jarOk);

  // Return the paths for download
  return { pomPath, jarPath };
}

function doDownload(token, pomPath, jarPath) {
  const hdrs = { Authorization: `Bearer ${token}` };

  const pomRes = http.get(`${BASE_URL}/repository/${REPO_NAME}/${pomPath}`, { headers: hdrs });
  downloadLatency.add(pomRes.timings.duration);
  totalRequests.add(1);
  const pomOk = check(pomRes, {
    "download POM ok": (r) => r.status === 200,
  });
  errorRate.add(!pomOk);

  const jarRes = http.get(`${BASE_URL}/repository/${REPO_NAME}/${jarPath}`, { headers: hdrs });
  downloadLatency.add(jarRes.timings.duration);
  totalRequests.add(1);
  const jarOk = check(jarRes, {
    "download JAR ok": (r) => r.status === 200,
  });
  errorRate.add(!jarOk);
}

function doBrowse(token) {
  const headers = authHeaders(token);

  const reposRes = http.get(`${BASE_URL}/api/v1/repositories`, { headers });
  browseLatency.add(reposRes.timings.duration);
  totalRequests.add(1);
  const reposOk = check(reposRes, {
    "browse repos ok": (r) => r.status === 200,
  });
  errorRate.add(!reposOk);

  const statusRes = http.get(`${BASE_URL}/api/v1/status`, { headers });
  browseLatency.add(statusRes.timings.duration);
  totalRequests.add(1);
  const statusOk = check(statusRes, {
    "status ok": (r) => r.status === 200,
  });
  errorRate.add(!statusOk);

  const usersRes = http.get(`${BASE_URL}/api/v1/security/users`, { headers });
  browseLatency.add(usersRes.timings.duration);
  totalRequests.add(1);
  const usersOk = check(usersRes, {
    "users ok": (r) => r.status === 200,
  });
  errorRate.add(!usersOk);
}

function doSearch(token) {
  const headers = authHeaders(token);
  const keyword = randomElement(SEARCH_KEYWORDS);

  const res = http.get(`${BASE_URL}/api/v1/search?q=${keyword}`, { headers });
  searchLatency.add(res.timings.duration);
  totalRequests.add(1);
  const ok = check(res, {
    "search ok": (r) => r.status === 200,
  });
  errorRate.add(!ok);
}

// --- Main VU function ---

export default function (data) {
  const token = data.token;

  // Each VU runs a mixed workload:
  //   40% upload+download, 30% browse, 30% search
  const roll = Math.random();

  if (roll < 0.4) {
    group("upload and download", () => {
      const paths = doUpload(token);
      sleep(0.3);
      doDownload(token, paths.pomPath, paths.jarPath);
    });
  } else if (roll < 0.7) {
    group("browse", () => {
      doBrowse(token);
    });
  } else {
    group("search", () => {
      doSearch(token);
    });
  }

  sleep(0.5 + Math.random() * 1.0); // think time: 0.5 - 1.5s
}
