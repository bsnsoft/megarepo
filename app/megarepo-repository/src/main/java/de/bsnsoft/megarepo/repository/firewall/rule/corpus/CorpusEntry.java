package de.bsnsoft.megarepo.repository.firewall.rule.corpus;

import java.util.Objects;

/**
 * One package name this instance already holds, as the typosquat comparison
 * sees it.
 *
 * <p>A name, not a component: every version of {@code pkg:npm/lodash} collapses
 * into one entry whose {@link #versions()} counts how many of them are stored.
 * A squat is a name that resembles a name, and the corpus is therefore keyed on
 * {@code (purl type, namespace, name)} — which also keeps it small enough to
 * hold in memory on the download path.
 *
 * @param purlType the purl type ({@code npm}, {@code maven}, {@code pypi}), not
 *     the repository format. Comparison is per ecosystem: a Maven artifact named
 *     {@code util} says nothing about an npm package named {@code utl}, and
 *     comparing across ecosystems would multiply both the work and the noise
 * @param namespace the purl namespace verbatim ({@code com.acme}, {@code @babel}),
 *     or null where the ecosystem has none
 * @param name the purl name verbatim
 * @param namespaceSkeleton {@link NameSkeleton#of(String)} of the namespace,
 *     precomputed because it is compared against on every download
 * @param nameSkeleton {@link NameSkeleton#of(String)} of the name
 * @param firstSegmentSkeleton the skeleton of the name's first separator-delimited
 *     segment — {@code lodash} for {@code lodash.get}. What makes a package
 *     family recognisable
 * @param versions how many distinct versions of this name are stored. The
 *     "popularity" the rule thresholds on: this instance's own usage, which is
 *     the only popularity signal available without an external feed and the more
 *     relevant one anyway — an attacker squats what this organisation depends on
 * @param hosted whether at least one of those versions lives in a hosted
 *     repository, i.e. was published here rather than proxied. Read by
 *     {@code NAMESPACE_CONFUSION}, which is about coordinates that belong to
 *     this organisation arriving from the internet
 * @param exampleRepository the name of a repository holding this package, for
 *     the evidence text — a developer reading "the namespace is published in
 *     'npm-internal'" can act on it, "the namespace is published here" is a
 *     riddle
 */
public record CorpusEntry(
        String purlType,
        String namespace,
        String name,
        String namespaceSkeleton,
        String nameSkeleton,
        String firstSegmentSkeleton,
        int versions,
        boolean hosted,
        String exampleRepository) {

    public CorpusEntry {
        Objects.requireNonNull(purlType, "purlType must not be null");
        Objects.requireNonNull(name, "name must not be null");
    }

    /** The coordinate as a developer would write it: {@code @babel/core}, {@code com.acme:util}. */
    public String coordinate() {
        if (namespace == null || namespace.isEmpty()) {
            return name;
        }
        return "maven".equals(purlType) ? namespace + ":" + name : namespace + "/" + name;
    }

    /**
     * Whether this entry <em>is</em> the coordinate that was requested.
     *
     * <p>Compared on the plain lower-cased form rather than the skeleton: a
     * package must never be reported as a typosquat of itself, but a look-alike
     * of it very much must be, and those two differ precisely in the characters
     * the skeleton folds away.
     */
    public boolean isSameCoordinateAs(String otherNamespace, String otherName) {
        return NameSkeleton.plain(namespace).equals(NameSkeleton.plain(otherNamespace))
                && NameSkeleton.plain(name).equals(NameSkeleton.plain(otherName));
    }
}
