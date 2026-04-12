INSERT INTO blob_stores (name, type, config) VALUES ('default', 'file', '{"path": "data/blobs/default"}');
INSERT INTO anonymous_access_settings (id, enabled, user_id, realm_name) VALUES (1, true, 'anonymous', 'NexusAuthorizingRealm');

INSERT INTO privileges (name, type, description, read_only, properties) VALUES
    ('nx-all', 'wildcard', 'All permissions', true, '{"pattern": "nexus:*"}'),
    ('nx-repository-view-*-*-*', 'repository-view', 'All repository view permissions', true, '{"format": "*", "repository": "*", "actions": "*"}'),
    ('nx-repository-admin-*-*-*', 'repository-admin', 'All repository admin permissions', true, '{"format": "*", "repository": "*", "actions": "*"}'),
    ('nx-blobstores-all', 'application', 'All blob store permissions', true, '{"domain": "blobstores", "actions": "*"}'),
    ('nx-users-all', 'application', 'All user permissions', true, '{"domain": "users", "actions": "*"}'),
    ('nx-roles-all', 'application', 'All role permissions', true, '{"domain": "roles", "actions": "*"}'),
    ('nx-repository-view-*-*-browse', 'repository-view', 'Browse all repositories', true, '{"format": "*", "repository": "*", "actions": "browse"}'),
    ('nx-repository-view-*-*-read', 'repository-view', 'Read all repositories', true, '{"format": "*", "repository": "*", "actions": "read"}');

INSERT INTO roles (id, name, description, source, read_only) VALUES
    ('nx-admin', 'nx-admin', 'Administrator Role', 'default', true),
    ('nx-anonymous', 'nx-anonymous', 'Anonymous Role', 'default', true);

INSERT INTO role_privileges (role_id, privilege_name) VALUES
    ('nx-admin', 'nx-all'),
    ('nx-anonymous', 'nx-repository-view-*-*-browse'),
    ('nx-anonymous', 'nx-repository-view-*-*-read');

-- Default admin user (password: admin123 - BCrypt hash)
INSERT INTO users (user_id, first_name, last_name, email, password_hash, status, source) VALUES
    ('admin', 'Administrator', '', 'admin@example.com', '$2a$10$kP5UMwSLkIITjQpBVfVJ4OLbszEagEoO5k1gIpmB7Ox2VmSTSEG3C', 'CHANGE_PASSWORD', 'default'),
    ('anonymous', 'Anonymous', 'User', 'anonymous@example.com', '$2a$10$kP5UMwSLkIITjQpBVfVJ4OLbszEagEoO5k1gIpmB7Ox2VmSTSEG3C', 'ACTIVE', 'default');

INSERT INTO user_roles (user_id, role_id) VALUES ('admin', 'nx-admin'), ('anonymous', 'nx-anonymous');

INSERT INTO scheduled_tasks (name, type, cron_expression, config) VALUES
    ('Cleanup repositories', 'repository.cleanup', '0 0 1 * * ?', '{}'),
    ('Compact blob store', 'blobstore.compact', '0 0 2 * * ?', '{"blobStoreName": "default"}'),
    ('Purge negative cache', 'proxy.negative-cache.purge', '0 */15 * * * ?', '{}');
