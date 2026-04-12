# MegaRepo License Keys

RSA 2048-bit keypair for signing and verifying MegaRepo licenses.

## Files

| File | Purpose | Committed? |
|------|---------|------------|
| `megarepo-private.pem` | Signs licenses (used by `sign-license.sh`) | NO (in .gitignore) |
| `megarepo-public.pem` | Verifies signatures (also embedded in `LicenseService.java`) | Yes |

## Generating a new keypair

```bash
cd tools/license-keys
openssl genrsa -out megarepo-private.pem 2048
openssl rsa -in megarepo-private.pem -pubout -out megarepo-public.pem
```

After regenerating, update the `PUBLIC_KEY_PEM` constant in
`app/megarepo-app/src/main/java/de/bsnsoft/megarepo/app/license/LicenseService.java`
with the base64 content from `megarepo-public.pem` (without the PEM headers).

## Signing a license

```bash
cd tools/license-signer
# Ensure megarepo-private.pem is in ../license-keys/ or symlinked here
ln -sf ../license-keys/megarepo-private.pem .
./sign-license.sh
```

The signed `.license` file is a JSON document that can be uploaded via the MegaRepo UI
(Administration > System > License) or mounted into the Docker container.
