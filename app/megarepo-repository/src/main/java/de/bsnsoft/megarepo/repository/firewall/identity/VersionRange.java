package de.bsnsoft.megarepo.repository.firewall.identity;

/**
 * A half-open version range as advisory feeds express it.
 *
 * <p>Shaped after the OSV {@code affected[].ranges[].events} model, which is
 * what {@code advisory_affected} stores ({@code introduced} / {@code fixed} /
 * {@code last_affected}):
 *
 * <ul>
 *   <li>{@code introduced} — <strong>inclusive</strong> lower bound. OSV writes
 *       the literal {@code "0"} for "since the beginning"; that value and
 *       {@code null} both mean unbounded here, so the generic scheme does not
 *       accidentally order a docker tag like {@code latest} below {@code "0"}.</li>
 *   <li>{@code fixed} — <strong>exclusive</strong> upper bound. The version that
 *       carries the fix is itself not affected.</li>
 *   <li>{@code lastAffected} — <strong>inclusive</strong> upper bound, used when
 *       an advisory names the last vulnerable version instead of the first
 *       fixed one.</li>
 * </ul>
 *
 * <p>OSV treats {@code fixed} and {@code last_affected} as mutually exclusive.
 * Advisory feeds are external input and do violate that, so this record accepts
 * both rather than rejecting the row: containment then applies both bounds,
 * i.e. the intersection. A range with no bound at all matches every version —
 * {@link #all()} makes that explicit.
 *
 * <p>Blank strings are normalised to {@code null} on construction.
 *
 * <p>Containment is evaluated by {@link VersionScheme#contains(VersionRange, String)},
 * because the ordering the bounds imply is ecosystem-specific.
 */
public record VersionRange(String introduced, String fixed, String lastAffected) {

    /** The OSV sentinel for "since the beginning of time". */
    private static final String BEGINNING = "0";

    public VersionRange {
        introduced = blankToNull(introduced);
        fixed = blankToNull(fixed);
        lastAffected = blankToNull(lastAffected);
    }

    /** Matches every version. */
    public static VersionRange all() {
        return new VersionRange(null, null, null);
    }

    /** Everything from {@code introduced} (inclusive) upwards, never fixed. */
    public static VersionRange from(String introduced) {
        return new VersionRange(introduced, null, null);
    }

    /** {@code [introduced, fixed)} — the common "vulnerable until the fix" shape. */
    public static VersionRange between(String introduced, String fixed) {
        return new VersionRange(introduced, fixed, null);
    }

    /** {@code (-inf, fixed)} — everything below the first fixed version. */
    public static VersionRange before(String fixed) {
        return new VersionRange(null, fixed, null);
    }

    /** {@code [introduced, lastAffected]} — both bounds inclusive. */
    public static VersionRange throughLastAffected(String introduced, String lastAffected) {
        return new VersionRange(introduced, null, lastAffected);
    }

    /**
     * {@code true} when {@code introduced} constrains anything, i.e. it is set
     * and is not the OSV {@code "0"} sentinel.
     */
    public boolean hasLowerBound() {
        return introduced != null && !BEGINNING.equals(introduced);
    }

    /** {@code true} when the range has no effective bound and matches everything. */
    public boolean isUnbounded() {
        return !hasLowerBound() && fixed == null && lastAffected == null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
