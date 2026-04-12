# Python/PyPI Test Project

Tests MegaRepo as a PyPI repository manager (proxy + hosted).

## What this tests

- **Proxy**: `pip install` fetches packages (requests, flask) through MegaRepo's
  `pypi-proxy` which proxies PyPI (pypi.org).
- **Hosted**: `twine upload` publishes a built package to `pypi-hosted`.
- **Config**: `pip.conf` points pip at MegaRepo instead of the default PyPI index.

## Prerequisites

- MegaRepo running at http://localhost:8080
- Repositories created via `../setup.sh`
- Python 3.9+ with pip installed
- twine installed (for upload test): `pip install twine build`

## Usage

```bash
# 1. Start MegaRepo
docker compose up  # from project root

# 2. Create repositories
cd test-projects && bash setup.sh

# 3. Install packages through proxy
cd python
PIP_CONFIG_FILE=pip.conf pip install -r requirements.txt --target=./lib

# 4. Build this test package
python -m build

# 5. Upload to hosted repo
twine upload --repository-url http://localhost:8080/repository/pypi-hosted/ \
    -u admin -p admin123 dist/*

# 6. Install from hosted repo
pip install --index-url http://localhost:8080/repository/pypi-hosted/simple/ \
    --trusted-host localhost megarepo-test-package
```

## Troubleshooting

- If pip fails with SSL errors, ensure `trusted-host = localhost` is in pip.conf.
- If twine upload fails with 401, verify credentials.
- If packages are not found through the proxy, ensure `pypi-proxy` was created
  with the correct remote URL (https://pypi.org/).
