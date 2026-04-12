#!/bin/bash
# Generate RSA keypair for MegaRepo license signing
# Run ONCE, keep private key SECRET

set -e

echo "=== MegaRepo License Key Generator ==="
echo ""

openssl genrsa -out megarepo-private.pem 2048 2>/dev/null
openssl rsa -in megarepo-private.pem -pubout -out megarepo-public.pem 2>/dev/null

echo "Generated:"
echo "  megarepo-private.pem  (KEEP SECRET! Used by sign-license.sh)"
echo "  megarepo-public.pem   (Embed in MegaRepo source code)"
echo ""
echo "Public key for LicenseService.java:"
echo ""
echo 'private static final String PUBLIC_KEY_PEM = """'
cat megarepo-public.pem
echo '""";'
