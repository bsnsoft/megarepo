# TLS/HTTPS Setup for MegaRepo

MegaRepo supports TLS natively via Spring Boot's built-in SSL configuration.
This is suitable for development, small deployments, or environments where a
reverse proxy is not desired. For production, see [nginx-docker-proxy.md](nginx-docker-proxy.md).

## Quick Start with Self-Signed Certificate

### 1. Generate a PKCS12 keystore

```bash
keytool -genkeypair \
  -alias megarepo \
  -keyalg RSA \
  -keysize 2048 \
  -storetype PKCS12 \
  -keystore keystore.p12 \
  -validity 3650 \
  -storepass changeit \
  -dname "CN=megarepo.example.com,O=MyOrg,L=City,ST=State,C=US"
```

### 2. Configure MegaRepo

Add the following to `application.yml` (or pass as environment variables):

```yaml
server:
  port: 443
  ssl:
    enabled: true
    key-store: file:/path/to/keystore.p12
    key-store-password: changeit
    key-store-type: PKCS12
```

Or via environment variables:

```bash
export SERVER_PORT=443
export SERVER_SSL_ENABLED=true
export SERVER_SSL_KEY_STORE=file:/path/to/keystore.p12
export SERVER_SSL_KEY_STORE_PASSWORD=changeit
export SERVER_SSL_KEY_STORE_TYPE=PKCS12
```

### 3. Start MegaRepo

```bash
./gradlew :megarepo-app:bootRun
# or with Docker:
docker run -p 443:443 \
  -v /path/to/keystore.p12:/keystore.p12 \
  -e SERVER_SSL_ENABLED=true \
  -e SERVER_SSL_KEY_STORE=file:/keystore.p12 \
  -e SERVER_SSL_KEY_STORE_PASSWORD=changeit \
  bsnsoft/megarepo
```

## Using Let's Encrypt Certificates

### 1. Obtain a certificate with certbot

```bash
sudo certbot certonly --standalone -d megarepo.example.com
```

### 2. Convert to PKCS12

Let's Encrypt produces PEM files. Convert them to a PKCS12 keystore:

```bash
openssl pkcs12 -export \
  -in /etc/letsencrypt/live/megarepo.example.com/fullchain.pem \
  -inkey /etc/letsencrypt/live/megarepo.example.com/privkey.pem \
  -out /opt/megarepo/keystore.p12 \
  -name megarepo \
  -passout pass:changeit
```

### 3. Configure as above

Point `server.ssl.key-store` to the generated `keystore.p12`.

### 4. Auto-renewal

Add a cron job or systemd timer to re-run certbot and convert the renewed
certificate:

```bash
# /etc/cron.d/megarepo-cert-renew
0 3 * * * root certbot renew --quiet && \
  openssl pkcs12 -export \
    -in /etc/letsencrypt/live/megarepo.example.com/fullchain.pem \
    -inkey /etc/letsencrypt/live/megarepo.example.com/privkey.pem \
    -out /opt/megarepo/keystore.p12 \
    -name megarepo \
    -passout pass:changeit && \
  systemctl restart megarepo
```

## Docker Daemon Configuration for Self-Signed Certificates

When using a self-signed certificate, the Docker daemon will refuse to connect
because it cannot verify the CA. You have two options:

### Option A: Add the CA to Docker's trust store (recommended)

```bash
# Extract the certificate from the keystore
keytool -exportcert -alias megarepo -keystore keystore.p12 \
  -storepass changeit -rfc > megarepo-ca.crt

# Copy to Docker's certificate directory
sudo mkdir -p /etc/docker/certs.d/megarepo.example.com
sudo cp megarepo-ca.crt /etc/docker/certs.d/megarepo.example.com/ca.crt

# No Docker restart needed - Docker checks this directory per-request
```

### Option B: Configure insecure registries (development only)

Edit `/etc/docker/daemon.json`:

```json
{
  "insecure-registries": ["megarepo.example.com:443"]
}
```

Then restart Docker:

```bash
sudo systemctl restart docker
```

**Warning**: This disables TLS verification entirely for the specified registry.
Only use this for local development.

## Verifying the Setup

```bash
# Test HTTPS endpoint
curl -k https://megarepo.example.com/api/v1/status

# Test Docker login
docker login megarepo.example.com

# Test Docker push
docker tag alpine megarepo.example.com/docker-hosted/alpine:test
docker push megarepo.example.com/docker-hosted/alpine:test
```
