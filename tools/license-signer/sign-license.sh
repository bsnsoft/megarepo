#!/bin/bash
# Sign a MegaRepo license file for a customer
# Requires: megarepo-private.pem in current directory

set -e

if [ ! -f megarepo-private.pem ]; then
    echo "ERROR: megarepo-private.pem not found. Run generate-keys.sh first."
    exit 1
fi

echo "=== MegaRepo License Signer ==="
echo "  BSNSoft Solutions GmbH"
echo ""

read -p "Company name: " COMPANY
read -p "Contact email: " EMAIL

ISSUED_AT=$(date +%Y-%m-%d)
VALID_UNTIL=$(date -v+1y +%Y-%m-%d 2>/dev/null || date -d "+1 year" +%Y-%m-%d)
LICENSE_ID="lic-$(uuidgen | tr '[:upper:]' '[:lower:]' 2>/dev/null || cat /proc/sys/kernel/random/uuid 2>/dev/null || echo $(date +%s))"

echo ""
echo "License details:"
echo "  Company:     $COMPANY"
echo "  Email:       $EMAIL"
echo "  Issued:      $ISSUED_AT"
echo "  Valid until:  $VALID_UNTIL"
echo "  License ID:  $LICENSE_ID"
echo ""
read -p "Sign this license? [y/N] " CONFIRM

if [ "$CONFIRM" != "y" ] && [ "$CONFIRM" != "Y" ]; then
    echo "Cancelled."
    exit 0
fi

# Create the data string to sign (must match LicenseService.java verification)
DATA="${COMPANY}|${EMAIL}|${ISSUED_AT}|${VALID_UNTIL}|${LICENSE_ID}"

# Sign with RSA-SHA256
SIGNATURE=$(echo -n "$DATA" | openssl dgst -sha256 -sign megarepo-private.pem | base64 | tr -d '\n')

# Build license JSON
OUTPUT="megarepo-${COMPANY// /-}.license"

cat > "$OUTPUT" << EOF
{
  "company": "$COMPANY",
  "email": "$EMAIL",
  "issuedAt": "$ISSUED_AT",
  "validUntil": "$VALID_UNTIL",
  "licenseId": "$LICENSE_ID",
  "signature": "$SIGNATURE"
}
EOF

echo ""
echo "License signed successfully!"
echo "  Output: $OUTPUT"
echo ""
echo "Customer instructions:"
echo "  Docker:  docker run -v \$(pwd)/$OUTPUT:/opt/megarepo/megarepo.license ..."
echo "  UI:      Upload via Administration > System > License"
echo ""
