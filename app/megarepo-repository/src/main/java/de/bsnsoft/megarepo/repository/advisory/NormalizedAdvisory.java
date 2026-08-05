package de.bsnsoft.megarepo.repository.advisory;

import java.time.Instant;
import java.util.List;

/**
 * A single advisory, normalised from whatever shape its source publishes.
 *
 * <p>Maps 1:1 onto {@code advisory} plus its {@code advisory_affected} rows. Every
 * {@link AdvisorySource} converts into this record so the rest of the firewall never sees
 * NVD's, OSV's or GHSA's own JSON.
 *
 * <p>{@code id} is the upstream identifier and the primary key — {@code CVE-2021-44228},
 * {@code GHSA-jfh8-c2jp-5v3q}, {@code MAL-2024-1234}. The same vulnerability reported by
 * two sources therefore yields two rows; collapsing them is the merge step's job, not the
 * source's.
 *
 * <p>{@code cvssScore} is nullable on purpose: malicious-package advisories ({@code MAL-})
 * carry no score, and a defaulted 0.0 would be indistinguishable from a genuine 0.0.
 * {@code withdrawnAt} non-null means the source retracted the advisory — such entries are
 * still ingested, so a previously flagged component can be cleared.
 *
 * @param id upstream advisory id, unique across sources, non-null
 * @param source source id, must equal the emitting {@link AdvisorySource#sourceId()}
 * @param summary short human-readable description, may be null
 * @param severity textual severity as published (e.g. {@code CRITICAL}), may be null
 * @param cvssScore CVSS base score, null when the source publishes none
 * @param cvssVector CVSS vector string, may be null
 * @param published first publication time, may be null
 * @param modified last modification time upstream, may be null
 * @param withdrawnAt retraction time, null unless the advisory was withdrawn
 * @param affected affected package ranges, never null, may be empty
 */
public record NormalizedAdvisory(
        String id,
        String source,
        String summary,
        String severity,
        Double cvssScore,
        String cvssVector,
        Instant published,
        Instant modified,
        Instant withdrawnAt,
        List<NormalizedAffected> affected) {}
