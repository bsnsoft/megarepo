CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE blob_stores (
    name            VARCHAR(200) PRIMARY KEY,
    type            VARCHAR(50)  NOT NULL,
    config          JSONB        NOT NULL DEFAULT '{}',
    state           VARCHAR(50)  NOT NULL DEFAULT 'STARTED',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE repositories (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL UNIQUE,
    format          VARCHAR(50)  NOT NULL,
    type            VARCHAR(20)  NOT NULL,
    online          BOOLEAN      NOT NULL DEFAULT TRUE,
    blob_store_name VARCHAR(200) NOT NULL REFERENCES blob_stores(name),
    attributes      JSONB        NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_repositories_format ON repositories(format);
CREATE INDEX idx_repositories_type ON repositories(type);

CREATE TABLE group_members (
    group_repo_id   UUID    NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    member_repo_id  UUID    NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (group_repo_id, member_repo_id)
);
CREATE INDEX idx_group_members_group ON group_members(group_repo_id);

CREATE TABLE components (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id   UUID         NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    format          VARCHAR(50)  NOT NULL,
    namespace       VARCHAR(500),
    name            VARCHAR(500) NOT NULL,
    version         VARCHAR(200) NOT NULL,
    attributes      JSONB        NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (repository_id, namespace, name, version)
);
CREATE INDEX idx_components_repo ON components(repository_id);
CREATE INDEX idx_components_name ON components(name);
CREATE INDEX idx_components_namespace_name ON components(namespace, name);
CREATE INDEX idx_components_format ON components(format);
CREATE INDEX idx_components_name_trgm ON components USING gin (name gin_trgm_ops);

CREATE TABLE assets (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id   UUID          NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    component_id    UUID          REFERENCES components(id) ON DELETE SET NULL,
    format          VARCHAR(50)   NOT NULL,
    path            VARCHAR(2048) NOT NULL,
    blob_ref        VARCHAR(500),
    content_type    VARCHAR(200),
    size            BIGINT,
    checksum_md5    VARCHAR(32),
    checksum_sha1   VARCHAR(40),
    checksum_sha256 VARCHAR(64),
    checksum_sha512 VARCHAR(128),
    generated       BOOLEAN       NOT NULL DEFAULT FALSE,
    last_downloaded TIMESTAMPTZ,
    last_modified   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(200),
    created_by_ip   VARCHAR(45),
    attributes      JSONB         NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    UNIQUE (repository_id, path)
);
CREATE INDEX idx_assets_repo ON assets(repository_id);
CREATE INDEX idx_assets_component ON assets(component_id);
CREATE INDEX idx_assets_path ON assets(path);
CREATE INDEX idx_assets_repo_path ON assets(repository_id, path);
CREATE INDEX idx_assets_checksum_sha1 ON assets(checksum_sha1);
CREATE INDEX idx_assets_checksum_sha256 ON assets(checksum_sha256);
CREATE INDEX idx_assets_last_downloaded ON assets(last_downloaded);

CREATE TABLE negative_cache (
    repository_id   UUID          NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    path            VARCHAR(2048) NOT NULL,
    cached_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (repository_id, path)
);
CREATE INDEX idx_negative_cache_expires ON negative_cache(expires_at);

CREATE TABLE users (
    user_id       VARCHAR(200) PRIMARY KEY,
    first_name    VARCHAR(200) NOT NULL,
    last_name     VARCHAR(200) NOT NULL,
    email         VARCHAR(500) NOT NULL,
    password_hash VARCHAR(500) NOT NULL,
    status        VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
    source        VARCHAR(100) NOT NULL DEFAULT 'default',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE roles (
    id          VARCHAR(200) PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    source      VARCHAR(100) NOT NULL DEFAULT 'default',
    read_only   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE role_privileges (
    role_id        VARCHAR(200) NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    privilege_name VARCHAR(200) NOT NULL,
    PRIMARY KEY (role_id, privilege_name)
);

CREATE TABLE role_roles (
    parent_role_id VARCHAR(200) NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    child_role_id  VARCHAR(200) NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (parent_role_id, child_role_id)
);

CREATE TABLE user_roles (
    user_id VARCHAR(200) NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    role_id VARCHAR(200) NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE privileges (
    name        VARCHAR(200) PRIMARY KEY,
    type        VARCHAR(50)  NOT NULL,
    description TEXT,
    read_only   BOOLEAN      NOT NULL DEFAULT FALSE,
    properties  JSONB        NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE anonymous_access_settings (
    id         INTEGER      PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    user_id    VARCHAR(200) NOT NULL DEFAULT 'anonymous',
    realm_name VARCHAR(200) NOT NULL DEFAULT 'NexusAuthorizingRealm'
);

CREATE TABLE cleanup_policies (
    name       VARCHAR(200) PRIMARY KEY,
    format     VARCHAR(50),
    notes      TEXT,
    criteria   JSONB        NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE repository_cleanup_policies (
    repository_id UUID         NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    policy_name   VARCHAR(200) NOT NULL REFERENCES cleanup_policies(name) ON DELETE CASCADE,
    PRIMARY KEY (repository_id, policy_name)
);

CREATE TABLE scheduled_tasks (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    type            VARCHAR(200) NOT NULL,
    cron_expression VARCHAR(200),
    config          JSONB        NOT NULL DEFAULT '{}',
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    current_state   VARCHAR(50)  NOT NULL DEFAULT 'WAITING',
    last_run        TIMESTAMPTZ,
    last_run_result VARCHAR(50),
    next_run        TIMESTAMPTZ,
    message         TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE routing_rules (
    name        VARCHAR(200) PRIMARY KEY,
    description TEXT,
    mode        VARCHAR(20)  NOT NULL DEFAULT 'BLOCK',
    matchers    JSONB        NOT NULL DEFAULT '[]',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
