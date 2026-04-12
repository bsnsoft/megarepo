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

ENTRYPOINT ["java", "-jar", "app.jar"]
