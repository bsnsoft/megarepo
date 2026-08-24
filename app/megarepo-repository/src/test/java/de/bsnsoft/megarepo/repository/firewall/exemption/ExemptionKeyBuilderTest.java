package de.bsnsoft.megarepo.repository.firewall.exemption;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallComponentKeyKind;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionScope;
import de.bsnsoft.megarepo.database.entity.FirewallExemptionEntity;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The key forms an exemption can name a component by, and the comparison that
 * decides whether a stored row covers it.
 *
 * <p>Every positive assertion here has a negative one beside it. A matcher test
 * that only shows what matches cannot tell a correct implementation from
 * {@code return true}, and this matcher's failure mode — matching more than the
 * V8 one did — is a hole nobody opened rather than a build that breaks loudly.
 */
class ExemptionKeyBuilderTest {

    private static final String PURL = "pkg:maven/com.acme/util@1.0.0";
    private static final String PURL_VERSIONLESS = "pkg:maven/com.acme/util";
    private static final String LEGACY = "maven2:com.acme:util:1.0.0";
    private static final String LEGACY_VERSIONLESS = "maven2:com.acme:util";

    private static ComponentIdentity maven(String group, String artifact, String version)
            throws Exception {
        return new ComponentIdentity.Purl(new PackageURL("maven", group, artifact, version, null, null));
    }

    @Nested
    @DisplayName("purl key forms")
    class PurlForms {

        @Test
        @DisplayName("a component is named by its purl and by the version-less purl")
        void bothPurlForms() throws Exception {
            var keys = ExemptionKeyBuilder.candidates(maven("com.acme", "util", "1.0.0"), false);

            assertThat(keys.purlVersionKeys()).containsExactly(PURL);
            assertThat(keys.purlComponentKeys()).containsExactly(PURL_VERSIONLESS);
            assertThat(keys.all()).containsExactlyInAnyOrder(PURL, PURL_VERSIONLESS);
        }

        @Test
        @DisplayName("qualifiers stay on the version key and drop off the component key")
        void qualifiersBelongToTheVersion() throws Exception {
            var sources = new ComponentIdentity.Purl(new PackageURL(
                    "maven",
                    "com.acme",
                    "util",
                    "1.0.0",
                    new java.util.TreeMap<>(java.util.Map.of("classifier", "sources")),
                    null));

            var keys = ExemptionKeyBuilder.candidates(sources, false);

            assertThat(keys.purlVersionKeys())
                    .as("the sources jar and the main jar are two stored artifacts")
                    .containsExactly("pkg:maven/com.acme/util@1.0.0?classifier=sources");
            assertThat(keys.purlComponentKeys())
                    .as("every version of a component covers every artifact of it")
                    .containsExactly(PURL_VERSIONLESS);
        }

        @Test
        @DisplayName("a content digest has no version, so both forms are the digest")
        void hashIdentity() {
            var keys = ExemptionKeyBuilder.candidates(ComponentIdentity.Hash.sha256("abc123"), true);

            assertThat(keys.purlVersionKeys()).containsExactly("sha256:abc123");
            assertThat(keys.purlComponentKeys()).containsExactly("sha256:abc123");
            assertThat(keys.legacyVersionKeys())
                    .as("a hash carries no coordinates, so no V8 key can be rebuilt from it")
                    .isEmpty();
        }

        @Test
        @DisplayName("an unidentified component strips its version at the '@'")
        void unidentifiedIdentity() {
            var identity = new ComponentIdentity.Unidentified("raw", null, "blob.bin", "3");

            var keys = ExemptionKeyBuilder.candidates(identity, false);

            assertThat(keys.purlVersionKeys()).containsExactly("unidentified:raw//blob.bin@3");
            assertThat(keys.purlComponentKeys()).containsExactly("unidentified:raw//blob.bin");
        }

        @Test
        @DisplayName("a null identity names nothing at all")
        void nullIdentity() {
            assertThat(ExemptionKeyBuilder.candidates(null, true).isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("V8 legacy coordinate forms")
    class LegacyForms {

        @Test
        @DisplayName("a maven purl rebuilds both the maven2 key and its legacy alias")
        void mavenBothFormatKeys() throws Exception {
            var keys = ExemptionKeyBuilder.candidates(maven("com.acme", "util", "1.0.0"), true);

            assertThat(keys.legacyVersionKeys())
                    .containsExactlyInAnyOrder(LEGACY, "maven:com.acme:util:1.0.0");
            assertThat(keys.legacyComponentKeys())
                    .containsExactlyInAnyOrder(LEGACY_VERSIONLESS, "maven:com.acme:util");
        }

        @Test
        @DisplayName("an npm scope is offered with and without its '@'")
        void npmScopeSpellings() throws Exception {
            var scoped = new ComponentIdentity.Purl(
                    new PackageURL("npm", "@acme", "widget", "2.1.0", null, null));

            var keys = ExemptionKeyBuilder.candidates(scoped, true);

            assertThat(keys.legacyVersionKeys())
                    .containsExactlyInAnyOrder("npm:@acme:widget:2.1.0", "npm:acme:widget:2.1.0");
        }

        @Test
        @DisplayName("a missing namespace is written as the empty string, as V8 wrote it")
        void emptyNamespace() throws Exception {
            var pypi = new ComponentIdentity.Purl(
                    new PackageURL("pypi", null, "requests", "2.31.0", null, null));

            var keys = ExemptionKeyBuilder.candidates(pypi, true);

            assertThat(keys.legacyVersionKeys()).containsExactly("pypi::requests:2.31.0");
            assertThat(keys.legacyComponentKeys()).containsExactly("pypi::requests");
        }

        @Test
        @DisplayName("legacy forms are skipped when the caller says no legacy rows exist")
        void skippedWhenNotNeeded() throws Exception {
            var keys = ExemptionKeyBuilder.candidates(maven("com.acme", "util", "1.0.0"), false);

            assertThat(keys.legacyVersionKeys()).isEmpty();
            assertThat(keys.legacyComponentKeys()).isEmpty();
            assertThat(keys.all()).hasSize(2);
        }

        @Test
        @DisplayName("the version-less form cuts at the last colon, exactly as the V8 matcher did")
        void prefixRuleIsBugCompatible() throws Exception {
            // A version containing a colon: concatenating format:ns:name would
            // give a different string than V8's substring(0, lastIndexOf(':')),
            // and it is V8's answer that has to be reproduced.
            var odd = new ComponentIdentity.Purl(
                    new PackageURL("pypi", null, "weird", "1.0:rc1", null, null));

            var keys = ExemptionKeyBuilder.candidates(odd, true);

            assertThat(keys.legacyComponentKeys()).containsExactly("pypi::weird:1.0");
        }
    }

    @Nested
    @DisplayName("scope × key kind matching")
    class Matching {

        @Test
        @DisplayName("a VERSION row matches the exact version and no other")
        void versionScope() throws Exception {
            var keys = ExemptionKeyBuilder.candidates(maven("com.acme", "util", "1.0.0"), true);

            assertThat(keys.covers(row(PURL, FirewallComponentKeyKind.PURL, FirewallExemptionScope.VERSION)))
                    .isTrue();
            assertThat(keys.covers(row(
                            "pkg:maven/com.acme/util@1.0.1",
                            FirewallComponentKeyKind.PURL,
                            FirewallExemptionScope.VERSION)))
                    .as("a neighbouring version is a different component version")
                    .isFalse();
        }

        @Test
        @DisplayName("a COMPONENT row matches every version, and never through the version key")
        void componentScope() throws Exception {
            var oneOh = ExemptionKeyBuilder.candidates(maven("com.acme", "util", "1.0.0"), true);
            var twoOh = ExemptionKeyBuilder.candidates(maven("com.acme", "util", "2.0.0"), true);

            var componentRow = row(
                    PURL_VERSIONLESS, FirewallComponentKeyKind.PURL, FirewallExemptionScope.COMPONENT);
            assertThat(oneOh.covers(componentRow)).isTrue();
            assertThat(twoOh.covers(componentRow)).isTrue();

            // The V8 ambiguity, made impossible: one column meaning both.
            assertThat(oneOh.covers(row(
                            PURL, FirewallComponentKeyKind.PURL, FirewallExemptionScope.COMPONENT)))
                    .as("a COMPONENT row holding a version-bearing key matches nothing")
                    .isFalse();
            assertThat(oneOh.covers(row(
                            PURL_VERSIONLESS,
                            FirewallComponentKeyKind.PURL,
                            FirewallExemptionScope.VERSION)))
                    .as("a VERSION row holding a version-less key matches nothing")
                    .isFalse();
        }

        @Test
        @DisplayName("a migrated legacy row keeps working — one version, and every version")
        void legacyRowsMatch() throws Exception {
            var oneOh = ExemptionKeyBuilder.candidates(maven("com.acme", "util", "1.0.0"), true);
            var twoOh = ExemptionKeyBuilder.candidates(maven("com.acme", "util", "2.0.0"), true);

            // V18: three colons -> VERSION.
            var pinned = row(LEGACY, FirewallComponentKeyKind.LEGACY_COORDINATE, FirewallExemptionScope.VERSION);
            assertThat(oneOh.covers(pinned)).isTrue();
            assertThat(twoOh.covers(pinned)).isFalse();

            // V18: two colons -> COMPONENT, the old prefix rule.
            var everyVersion = row(
                    LEGACY_VERSIONLESS,
                    FirewallComponentKeyKind.LEGACY_COORDINATE,
                    FirewallExemptionScope.COMPONENT);
            assertThat(oneOh.covers(everyVersion)).isTrue();
            assertThat(twoOh.covers(everyVersion)).isTrue();
        }

        @Test
        @DisplayName("a legacy key never matches a PURL row and vice versa")
        void keyKindsDoNotCross() throws Exception {
            var keys = ExemptionKeyBuilder.candidates(maven("com.acme", "util", "1.0.0"), true);

            assertThat(keys.covers(row(
                            LEGACY, FirewallComponentKeyKind.PURL, FirewallExemptionScope.VERSION)))
                    .isFalse();
            assertThat(keys.covers(row(
                            PURL,
                            FirewallComponentKeyKind.LEGACY_COORDINATE,
                            FirewallExemptionScope.VERSION)))
                    .isFalse();
        }

        @Test
        @DisplayName("legacy rows match nothing when the legacy forms were not built")
        void legacyNeedsTheFlag() throws Exception {
            var keys = ExemptionKeyBuilder.candidates(maven("com.acme", "util", "1.0.0"), false);

            assertThat(keys.covers(row(
                            LEGACY,
                            FirewallComponentKeyKind.LEGACY_COORDINATE,
                            FirewallExemptionScope.VERSION)))
                    .isFalse();
        }

        @Test
        @DisplayName("a row for an entirely different component matches nothing")
        void unrelatedComponent() throws Exception {
            var keys = ExemptionKeyBuilder.candidates(maven("com.acme", "util", "1.0.0"), true);

            assertThat(keys.covers(row(
                            "pkg:maven/org.other/util@1.0.0",
                            FirewallComponentKeyKind.PURL,
                            FirewallExemptionScope.VERSION)))
                    .as("the namespace is part of the identity — the CPE collapse this replaces")
                    .isFalse();
            assertThat(keys.covers(row(
                            "maven2:org.other:util",
                            FirewallComponentKeyKind.LEGACY_COORDINATE,
                            FirewallExemptionScope.COMPONENT)))
                    .isFalse();
        }

        @Test
        @DisplayName("a row with no key covers nothing rather than throwing")
        void nullKey() throws Exception {
            var keys = ExemptionKeyBuilder.candidates(maven("com.acme", "util", "1.0.0"), true);

            assertThat(keys.covers(null)).isFalse();
            assertThat(keys.covers(row(null, FirewallComponentKeyKind.PURL, FirewallExemptionScope.VERSION)))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("storage key normalisation")
    class StorageKeys {

        @Test
        @DisplayName("a COMPONENT-scoped request is stored under the version-less key")
        void componentScopeStripsTheVersion() {
            assertThat(ExemptionKeyBuilder.storageKey(PURL, FirewallExemptionScope.COMPONENT))
                    .isEqualTo(PURL_VERSIONLESS);
        }

        @Test
        @DisplayName("a VERSION-scoped request keeps its version")
        void versionScopeKeepsIt() {
            assertThat(ExemptionKeyBuilder.storageKey(PURL, FirewallExemptionScope.VERSION))
                    .isEqualTo(PURL);
        }

        @Test
        @DisplayName("a digest is not maimed by 'strip the version'")
        void digestSurvivesComponentScope() {
            assertThat(ExemptionKeyBuilder.storageKey("sha256:abc123", FirewallExemptionScope.COMPONENT))
                    .isEqualTo("sha256:abc123");
        }

        @Test
        @DisplayName("an unparseable key is kept verbatim instead of being guessed at")
        void garbageIsKept() {
            assertThat(ExemptionKeyBuilder.storageKey("pkg:not a purl", FirewallExemptionScope.COMPONENT))
                    .isEqualTo("pkg:not a purl");
        }
    }

    private static FirewallExemptionEntity row(
            String key, FirewallComponentKeyKind kind, FirewallExemptionScope scope) {
        FirewallExemptionEntity entity = new FirewallExemptionEntity();
        entity.setComponentKey(key);
        entity.setKeyKind(kind);
        entity.setScopeType(scope);
        return entity;
    }
}
