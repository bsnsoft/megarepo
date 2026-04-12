# nginx Reverse Proxy for MegaRepo (Recommended for Production)

This guide sets up nginx as a TLS-terminating reverse proxy in front of MegaRepo.
This is the recommended production setup because:

- Let's Encrypt integration is seamless with certbot
- nginx handles TLS termination efficiently
- No need to convert certificates to Java keystores
- Battle-tested configuration for Docker registry proxying

## nginx Configuration

Save as `/etc/nginx/sites-available/megarepo` (or use the docker-compose setup below):

```nginx
upstream megarepo {
    server 127.0.0.1:8080;
    keepalive 32;
}

server {
    listen 80;
    server_name megarepo.example.com;

    # Redirect all HTTP to HTTPS
    location / {
        return 301 https://$host$request_uri;
    }

    # Let's Encrypt challenge
    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }
}

server {
    listen 443 ssl;
    http2 on;
    server_name megarepo.example.com;

    # TLS certificates (Let's Encrypt)
    ssl_certificate     /etc/letsencrypt/live/megarepo.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/megarepo.example.com/privkey.pem;

    # Modern TLS configuration
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384;
    ssl_prefer_server_ciphers off;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 1d;
    ssl_session_tickets off;

    # HSTS
    add_header Strict-Transport-Security "max-age=63072000" always;

    # Docker requires no upload size limit for large image layers
    client_max_body_size 0;

    # Required for Docker chunked uploads
    chunked_transfer_encoding on;

    location / {
        proxy_pass http://megarepo;

        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Port  $server_port;

        # Large image pushes can take a long time
        proxy_read_timeout    900;
        proxy_send_timeout    900;
        proxy_connect_timeout 60;

        # Required for streaming Docker blob uploads
        proxy_request_buffering off;
        proxy_buffering         off;

        # WebSocket support (optional, for future live UI updates)
        proxy_http_version 1.1;
        proxy_set_header   Upgrade    $http_upgrade;
        proxy_set_header   Connection $connection_upgrade;
    }
}

# WebSocket upgrade map
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}
```

## Docker Compose (Full Stack with nginx + certbot)

Save as `docker-compose.prod.yml`:

```yaml
services:
  megarepo:
    image: bsnsoft/megarepo:latest
    restart: unless-stopped
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/megarepo?stringtype=unspecified
      SPRING_DATASOURCE_USERNAME: megarepo
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      MEGAREPO_JWT_SECRET: ${JWT_SECRET}
      SERVER_FORWARD_HEADERS_STRATEGY: native
    volumes:
      - megarepo-data:/app/data
    depends_on:
      db:
        condition: service_healthy
    networks:
      - internal

  db:
    image: postgres:16-alpine
    restart: unless-stopped
    environment:
      POSTGRES_DB: megarepo
      POSTGRES_USER: megarepo
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U megarepo"]
      interval: 5s
      timeout: 5s
      retries: 5
    networks:
      - internal

  nginx:
    image: nginx:1.27-alpine
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx.conf:/etc/nginx/conf.d/default.conf:ro
      - certbot-etc:/etc/letsencrypt:ro
      - certbot-var:/var/www/certbot:ro
    depends_on:
      - megarepo
    networks:
      - internal

  certbot:
    image: certbot/certbot
    volumes:
      - certbot-etc:/etc/letsencrypt
      - certbot-var:/var/www/certbot
    # Run once to obtain cert, then use cron for renewal
    entrypoint: "/bin/sh -c 'trap exit TERM; while :; do certbot renew --quiet; sleep 12h & wait $${!}; done'"

volumes:
  megarepo-data:
  postgres-data:
  certbot-etc:
  certbot-var:

networks:
  internal:
```

When using docker-compose, change the nginx upstream to:

```nginx
upstream megarepo {
    server megarepo:8080;
    keepalive 32;
}
```

## Initial Setup

### 1. Create environment file

```bash
cat > .env <<EOF
DB_PASSWORD=$(openssl rand -base64 32)
JWT_SECRET=$(openssl rand -base64 64)
EOF
```

### 2. Obtain initial Let's Encrypt certificate

```bash
# Start nginx temporarily without SSL for the ACME challenge
docker compose -f docker-compose.prod.yml up -d nginx

# Obtain certificate
docker compose -f docker-compose.prod.yml run --rm certbot \
  certonly --webroot --webroot-path=/var/www/certbot \
  --email admin@example.com --agree-tos --no-eff-email \
  -d megarepo.example.com

# Restart with full config
docker compose -f docker-compose.prod.yml down
docker compose -f docker-compose.prod.yml up -d
```

### 3. Set up automatic certificate renewal

```bash
# Add to crontab
echo "0 5 * * * cd /opt/megarepo && docker compose -f docker-compose.prod.yml run --rm certbot renew --quiet && docker compose -f docker-compose.prod.yml exec nginx nginx -s reload" | sudo tee /etc/cron.d/megarepo-certbot
```

## Docker Daemon Configuration

With a valid Let's Encrypt certificate, no special Docker daemon configuration
is needed. Docker will trust the certificate automatically.

For custom domains or internal CAs, see [tls-setup.md](tls-setup.md) for
Docker daemon trust configuration.

## Verifying the Setup

```bash
# Test HTTPS
curl https://megarepo.example.com/api/v1/status

# Test Docker
docker login megarepo.example.com
docker pull megarepo.example.com/docker-hosted/alpine:latest
```
