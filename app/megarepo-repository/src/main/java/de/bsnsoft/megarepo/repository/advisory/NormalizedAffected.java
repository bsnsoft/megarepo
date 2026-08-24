package de.bsnsoft.megarepo.repository.advisory;

/**
 * One affected package range of an advisory, already normalised to purl components.
 *
 * <p>Maps 1:1 onto {@code advisory_affected}. The version bounds are kept as the source
 * published them — interpreting them is the job of the ecosystem's {@code VersionScheme},
 * because the same string orders differently per ecosystem. Do not pre-parse them here.
 *
 * <p>Bound semantics follow OSV: {@code introduced} is inclusive, {@code fixed} is
 * exclusive, {@code lastAffected} is inclusive. A source that only publishes an opaque
 * range string (NVD's CPE ranges, for instance) leaves the three bounds null and fills
 * {@code versionRange} only.
 *
 * @param purlType purl type, e.g. {@code maven}, {@code npm}, {@code pypi}, {@code nuget}
 * @param purlNamespace purl namespace, null for ecosystems without one (nuget, pypi)
 * @param purlName purl name, already normalised the way the ecosystem's PurlMapper does it
 * @param versionRange the raw range expression as published, null if only bounds are given
 * @param introduced first affected version, inclusive; null means "from the beginning"
 * @param fixed first fixed version, exclusive; null means "no fix known"
 * @param lastAffected last affected version, inclusive; alternative to {@code fixed}
 */
public record NormalizedAffected(
        String purlType,
        String purlNamespace,
        String purlName,
        String versionRange,
        String introduced,
        String fixed,
        String lastAffected) {}
