import http from "k6/http";
import { check, group, sleep } from "k6";
import { Trend, Rate, Counter } from "k6/metrics";
import { login, authHeaders, uploadHeaders, BASE_URL } from "./auth.js";

// Custom metrics
const uploadLatency = new Trend("upload_latency", true);
const downloadLatency = new Trend("download_latency", true);
const uploadErrors = new Rate("upload_errors");
const downloadErrors = new Rate("download_errors");
const artifactsUploaded = new Counter("artifacts_uploaded");
const artifactsDownloaded = new Counter("artifacts_downloaded");

const REPO_NAME = "loadtest-maven-hosted";

export const options = {
  scenarios: {
    upload_download: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "30s", target: 10 },
        { duration: "1m", target: 10 },
        { duration: "15s", target: 0 },
      ],
    },
  },
  thresholds: {
    upload_latency: ["p(95)<1000"],
    download_latency: ["p(95)<500"],
    upload_errors: ["rate<0.05"],
    download_errors: ["rate<0.01"],
  },
};

const GROUP_IDS = [
  "com.loadtest.alpha",
  "com.loadtest.beta",
  "org.loadtest.gamma",
  "io.loadtest.delta",
  "net.loadtest.epsilon",
];

const ARTIFACT_IDS = [
  "core-lib",
  "data-utils",
  "web-framework",
  "auth-module",
  "cache-engine",
  "config-reader",
  "event-bus",
  "metrics-collector",
];

const VERSIONS = [
  "1.0.0", "1.0.1", "1.1.0", "1.2.0", "2.0.0",
  "2.0.1", "2.1.0", "3.0.0-SNAPSHOT",
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

function generateJarContent(size) {
  // Generate a fake JAR payload of the given size (bytes)
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  let result = "";
  for (let i = 0; i < size; i++) {
    result += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return result;
}

function mavenPath(groupId, artifactId, version, filename) {
  const groupPath = groupId.replace(/\./g, "/");
  return `${groupPath}/${artifactId}/${version}/${filename}`;
}

/**
 * Ensure the loadtest repository exists. Called once during setup.
 */
function ensureRepository(token) {
  const headers = authHeaders(token);
  const res = http.get(`${BASE_URL}/api/v1/repositories/${REPO_NAME}`, { headers });

  if (res.status === 200) {
    return; // already exists
  }

  const createRes = http.post(
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

  check(createRes, {
    "repository created or exists": (r) => r.status === 201 || r.status === 200 || r.status === 409,
  });
}

export function setup() {
  const token = login();
  ensureRepository(token);
  return { token };
}

export default function (data) {
  const token = data.token;

  const groupId = randomElement(GROUP_IDS);
  const artifactId = randomElement(ARTIFACT_IDS);
  const version = randomElement(VERSIONS);
  const vuSuffix = `vu${__VU}-iter${__ITER}`;

  // Use VU-specific version to avoid collisions
  const uniqueVersion = `${version}-${vuSuffix}`;

  group("upload artifact", () => {
    // Upload POM
    const pomPath = mavenPath(groupId, artifactId, uniqueVersion, `${artifactId}-${uniqueVersion}.pom`);
    const pomContent = generatePom(groupId, artifactId, uniqueVersion);
    const pomRes = http.put(
      `${BASE_URL}/repository/${REPO_NAME}/${pomPath}`,
      pomContent,
      { headers: uploadHeaders(token, "application/xml") }
    );

    uploadLatency.add(pomRes.timings.duration);
    const pomOk = check(pomRes, {
      "POM upload succeeds": (r) => r.status >= 200 && r.status < 300,
    });
    uploadErrors.add(!pomOk);
    if (pomOk) artifactsUploaded.add(1);

    // Upload JAR (small 2KB fake payload)
    const jarPath = mavenPath(groupId, artifactId, uniqueVersion, `${artifactId}-${uniqueVersion}.jar`);
    const jarContent = generateJarContent(2048);
    const jarRes = http.put(
      `${BASE_URL}/repository/${REPO_NAME}/${jarPath}`,
      jarContent,
      { headers: uploadHeaders(token, "application/java-archive") }
    );

    uploadLatency.add(jarRes.timings.duration);
    const jarOk = check(jarRes, {
      "JAR upload succeeds": (r) => r.status >= 200 && r.status < 300,
    });
    uploadErrors.add(!jarOk);
    if (jarOk) artifactsUploaded.add(1);
  });

  sleep(0.5);

  group("download artifact", () => {
    // Download the POM back
    const pomPath = mavenPath(groupId, artifactId, uniqueVersion, `${artifactId}-${uniqueVersion}.pom`);
    const pomRes = http.get(`${BASE_URL}/repository/${REPO_NAME}/${pomPath}`, {
      headers: { Authorization: `Bearer ${token}` },
    });

    downloadLatency.add(pomRes.timings.duration);
    const pomOk = check(pomRes, {
      "POM download succeeds": (r) => r.status === 200,
    });
    downloadErrors.add(!pomOk);
    if (pomOk) artifactsDownloaded.add(1);

    // Download the JAR back
    const jarPath = mavenPath(groupId, artifactId, uniqueVersion, `${artifactId}-${uniqueVersion}.jar`);
    const jarRes = http.get(`${BASE_URL}/repository/${REPO_NAME}/${jarPath}`, {
      headers: { Authorization: `Bearer ${token}` },
    });

    downloadLatency.add(jarRes.timings.duration);
    const jarOk = check(jarRes, {
      "JAR download succeeds": (r) => r.status === 200,
    });
    downloadErrors.add(!jarOk);
    if (jarOk) artifactsDownloaded.add(1);
  });

  sleep(0.5);

  group("search for artifact", () => {
    const searchRes = http.get(
      `${BASE_URL}/api/v1/search?q=${artifactId}&repository=${REPO_NAME}`,
      { headers: authHeaders(token) }
    );

    check(searchRes, {
      "search returns 200": (r) => r.status === 200,
    });
  });

  sleep(0.3);
}
