-- Fix bug: FirstRunSetup seeded default Maven repositories with format='maven',
-- but MavenFormatPlugin.getFormat() returns 'maven2' (Sonatype-Nexus convention).
-- Effect on running instances: every request to a default Maven repo throws
-- UnsupportedFormatException at request time, because FormatRegistry has no
-- plugin registered under the key "maven".
--
-- This migration normalises all stored format strings from 'maven' to 'maven2'
-- in every table that carries a format column, so existing installations heal
-- on next deploy without manual DBA work.

UPDATE repositories
SET format = 'maven2'
WHERE format = 'maven';

UPDATE components
SET format = 'maven2'
WHERE format = 'maven';

UPDATE assets
SET format = 'maven2'
WHERE format = 'maven';

UPDATE cleanup_policies
SET format = 'maven2'
WHERE format = 'maven';

UPDATE audit_log
SET format = 'maven2'
WHERE format = 'maven';
