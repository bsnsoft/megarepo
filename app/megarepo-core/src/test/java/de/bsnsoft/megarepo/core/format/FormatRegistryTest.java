package de.bsnsoft.megarepo.core.format;

import de.bsnsoft.megarepo.core.repository.RepositoryType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link FormatRegistry} alias handling.
 *
 * <p>The Maven plugin canonicalises to {@code "maven2"} (Sonatype-Nexus
 * convention) but must continue to serve requests routed against
 * {@code "maven"} for legacy DB seeds, hand-rolled YAML presets, and any
 * configuration imported from older instances. This test fixes that
 * contract so a future refactor that drops aliases makes the regression
 * loud.
 */
class FormatRegistryTest {

    @Test
    void resolves_plugin_by_canonical_key() {
        FormatRegistry registry = new FormatRegistry();
        FormatPlugin plugin = stub("widget", Set.of());
        registry.register(plugin);

        assertThat(registry.getPlugin("widget")).isSameAs(plugin);
    }

    @Test
    void resolves_plugin_by_alias() {
        FormatRegistry registry = new FormatRegistry();
        FormatPlugin plugin = stub("maven2", Set.of("maven"));
        registry.register(plugin);

        assertThat(registry.getPlugin("maven")).isSameAs(plugin);
        assertThat(registry.getPlugin("maven2")).isSameAs(plugin);
    }

    @Test
    void getSupportedFormats_excludes_aliases() {
        FormatRegistry registry = new FormatRegistry();
        registry.register(stub("maven2", Set.of("maven")));
        registry.register(stub("npm", Set.of()));

        assertThat(registry.getSupportedFormats())
                .containsExactlyInAnyOrder("maven2", "npm")
                .doesNotContain("maven");
    }

    @Test
    void getResolvableFormatKeys_includes_aliases() {
        FormatRegistry registry = new FormatRegistry();
        registry.register(stub("maven2", Set.of("maven")));

        assertThat(registry.getResolvableFormatKeys())
                .containsExactlyInAnyOrder("maven2", "maven");
    }

    @Test
    void alias_does_not_override_existing_canonical_plugin() {
        FormatRegistry registry = new FormatRegistry();
        FormatPlugin canonical = stub("maven", Set.of());
        FormatPlugin withAlias = stub("maven2", Set.of("maven"));

        registry.register(canonical);
        registry.register(withAlias);

        // The "maven" canonical plugin stays — alias must not shadow it.
        assertThat(registry.getPlugin("maven")).isSameAs(canonical);
        assertThat(registry.getPlugin("maven2")).isSameAs(withAlias);
    }

    @Test
    void unknown_format_throws() {
        FormatRegistry registry = new FormatRegistry();
        registry.register(stub("maven2", Set.of("maven")));

        assertThatThrownBy(() -> registry.getPlugin("pypi"))
                .isInstanceOf(UnsupportedFormatException.class);
    }

    @Test
    void blank_and_null_aliases_are_ignored() {
        FormatRegistry registry = new FormatRegistry();
        FormatPlugin plugin = stub("widget", Set.of("", "   "));
        registry.register(plugin);

        // No false positives — only the canonical key resolves.
        assertThat(registry.getResolvableFormatKeys()).containsExactly("widget");
    }

    private FormatPlugin stub(String format, Set<String> aliases) {
        return new FormatPlugin() {
            @Override public String getFormat() { return format; }
            @Override public Set<String> getAliases() { return aliases; }
            @Override public String getDisplayName() { return format; }
            @Override public Set<RepositoryType> getSupportedTypes() {
                return Set.of(RepositoryType.HOSTED);
            }
            @Override public Optional<String> getDefaultRemoteUrl() { return Optional.empty(); }
            @Override public FormatRequestHandler getRequestHandler() { return null; }
            @Override public ComponentCoordinateExtractor getCoordinateExtractor() { return null; }
            @Override public Optional<FormatSearchContributor> getSearchContributor() {
                return Optional.empty();
            }
            @Override public UploadDefinition getUploadDefinition() { return null; }
            @Override public void validateRepositoryConfig(RepositoryType type, Map<String, Object> attributes) { }
        };
    }
}
