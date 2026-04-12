-- Add nx-viewer role for LDAP users (default role when no LDAP groups are mapped).
-- nx-viewer has the same read-only privileges as nx-anonymous: browse + read all repositories.

INSERT INTO roles (id, name, description, source, read_only)
VALUES ('nx-viewer', 'nx-viewer', 'Read-only viewer role (default for LDAP users)', 'default', true);

INSERT INTO role_privileges (role_id, privilege_name)
VALUES ('nx-viewer', 'nx-repository-view-*-*-browse'),
       ('nx-viewer', 'nx-repository-view-*-*-read');
