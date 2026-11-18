FROM eclipse-temurin:21-jre-alpine

ARG VERSION=unknown

LABEL maintainer="bsnsoft.de"
LABEL description="MegaRepo - Affordable Artifact Repository Manager"
LABEL version="${VERSION}"

# Patch Alpine OS-level CVEs
RUN apk upgrade --no-cache

RUN addgroup -S megarepo && adduser -S megarepo -G megarepo

WORKDIR /opt/megarepo

COPY megarepo-app/build/libs/megarepo.jar app.jar

RUN mkdir -p data/blobs/default && chown -R megarepo:megarepo /opt/megarepo

USER megarepo

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8081/api/v1/status || exit 1

# -XX:-UseBASE64Intrinsics: mitigation for CVE-2026-114938 ("Java2Shell"), a heap
# overflow in the JIT's java.util.Base64 decoder on x86-64 CPUs with AVX-512 VBMI.
# MegaRepo decodes untrusted Base64 before authentication (HTTP Basic), and the
# Temurin 21 base image has no fixed build yet. Remove once eclipse-temurin:21
# ships a fixed JDK (> 21.0.13+9). The flag exists on all platforms, ARM64 included.
ENTRYPOINT ["java", "-XX:-UseBASE64Intrinsics", "-jar", "app.jar"]
