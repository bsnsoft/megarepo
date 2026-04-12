# npm Test Project

Tests MegaRepo as an npm registry (proxy + hosted).

## What this tests

- **Proxy**: `npm install` fetches packages (lodash, express) through MegaRepo's
  `npm-proxy` which proxies the npmjs.org registry.
- **Hosted**: `npm publish` pushes this package to `npm-hosted`.
- **Config**: `.npmrc` configures the MegaRepo registry and authentication.

## Prerequisites

- MegaRepo running at http://localhost:8080
- Repositories created via `../setup.sh`
- Node.js 18+ with npm installed

## Usage

```bash
# 1. Start MegaRepo
docker compose up  # from project root

# 2. Create repositories
cd test-projects && bash setup.sh

# 3. Install packages through proxy
cd npm
npm install

# 4. Verify the app works
npm test

# 5. Publish to hosted repo
npm publish --registry http://localhost:8080/repository/npm-hosted/

# 6. Verify published package
curl -u admin:admin123 http://localhost:8080/repository/npm-hosted/@megarepo-test/npm-test
```

## Authentication

The `.npmrc` file uses base64-encoded `admin:admin123` for `_auth`.
To regenerate:

```bash
echo -n 'admin:admin123' | base64
# Output: YWRtaW46YWRtaW4xMjM=
```

## Troubleshooting

- If `npm install` fails with ECONNREFUSED, ensure MegaRepo is running.
- If `npm publish` fails with 401, check the `_auth` value in `.npmrc`.
- If packages are not found through the proxy, ensure `npm-proxy` was created
  with the correct remote URL (https://registry.npmjs.org/).
