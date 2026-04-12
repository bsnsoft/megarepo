CREATE TABLE ssl_certificates (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    pem             TEXT         NOT NULL,
    subject_cn      VARCHAR(500),
    issuer_cn       VARCHAR(500),
    issuer_org      VARCHAR(500),
    fingerprint     VARCHAR(100) NOT NULL UNIQUE,
    issued_on       TIMESTAMPTZ,
    expires_on      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
