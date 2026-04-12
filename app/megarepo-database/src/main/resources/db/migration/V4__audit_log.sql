CREATE TABLE audit_log (
    id          BIGSERIAL    PRIMARY KEY,
    timestamp   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    user_id     VARCHAR(200),
    action      VARCHAR(20)  NOT NULL,
    repository  VARCHAR(200) NOT NULL,
    path        VARCHAR(2048),
    source_url  VARCHAR(2048),
    size        BIGINT,
    ip_address  VARCHAR(45),
    format      VARCHAR(50),
    duration_ms BIGINT
);

CREATE INDEX idx_audit_log_timestamp ON audit_log(timestamp);
CREATE INDEX idx_audit_log_repository ON audit_log(repository);
CREATE INDEX idx_audit_log_user ON audit_log(user_id);
CREATE INDEX idx_audit_log_action ON audit_log(action);
