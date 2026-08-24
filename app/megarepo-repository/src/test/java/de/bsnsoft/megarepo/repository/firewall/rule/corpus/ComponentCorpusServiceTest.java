package de.bsnsoft.megarepo.repository.firewall.rule.corpus;

import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlBuilder;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlMapper;
import com.github.packageurl.PackageURL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The corpus loader: what it reads, what it does when it cannot, and — the part
 * that matters on the download path — what it does <em>not</em> do when asked
 * for the corpus.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComponentCorpusServiceTest {

    private static final UUID PROXY_REPOSITORY = UUID.randomUUID();
    private static final UUID HOSTED_REPOSITORY = UUID.randomUUID();

    @Mock private ComponentJpaRepository components;
    @Mock private RepositoryJpaRepository repositories;

    private PurlBuilder purlBuilder;

    @BeforeEach
    void setUp() {
        purlBuilder = new PurlBuilder(List.of(new TestNpmPurlMapper()));
        when(repositories.findAll()).thenReturn(List.of(
                repository(PROXY_REPOSITORY, "npm-proxy", "PROXY"),
                repository(HOSTED_REPOSITORY, "npm-internal", "HOSTED")));
    }

    @Test
    @DisplayName("a scan turns stored components into names, with hosted taken from the repository")
    void scans() {
        givenComponents(
                component(PROXY_REPOSITORY, "lodash", "4.17.20"),
                component(PROXY_REPOSITORY, "lodash", "4.17.21"),
                component(HOSTED_REPOSITORY, "acme-tools", "1.0.0"));

        ComponentCorpusService service = service(ComponentCorpusProperties.defaults());
        service.runRefresh();

        ComponentNameCorpus corpus = service.corpus();
        assertThat(corpus.neverLoaded()).isFalse();
        assertThat(corpus.size()).isEqualTo(2);
        assertThat(corpus.scannedComponents()).isEqualTo(3);
        assertThat(corpus.byNameSkeleton("npm", "lodash"))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.versions()).isEqualTo(2);
                    assertThat(entry.hosted()).isFalse();
                });
        assertThat(corpus.hostedCoordinate("npm", null, "acme-tools")).isNotNull();
        assertThat(corpus.hostedCoordinate("npm", null, "lodash")).isNull();
    }

    @Test
    @DisplayName("asking for the corpus does not query the database")
    void readingIsFree() {
        ComponentCorpusService service = service(ComponentCorpusProperties.defaults());
        service.publish(CorpusFixtures.corpus().proxied("npm", null, "lodash", 1).build());

        for (int i = 0; i < 100; i++) {
            assertThat(service.corpus().size()).isEqualTo(1);
        }

        verify(components, never()).findAllByIdNotNull(any(Pageable.class));
    }

    @Test
    @DisplayName("a database failure keeps the previous corpus instead of taking downloads with it")
    void survivesFailure() {
        ComponentCorpusService service = service(ComponentCorpusProperties.defaults());
        service.publish(CorpusFixtures.corpus().proxied("npm", null, "lodash", 1).build());
        when(repositories.findAll()).thenThrow(new IllegalStateException("connection reset"));

        service.runRefresh();

        assertThat(service.corpus().size()).isEqualTo(1);
        assertThat(service.corpus().neverLoaded()).isFalse();
    }

    @Test
    @DisplayName("a switched-off corpus is empty and settled, never 'not loaded yet'")
    void disabled() {
        ComponentCorpusService service = service(new ComponentCorpusProperties(
                false, Duration.ofMinutes(30), 50, 1000L));

        ComponentNameCorpus corpus = service.corpus();

        // The distinction NAMESPACE_CONFUSION reads: "off" is an answer,
        // "not loaded yet" is a request to come back later.
        assertThat(corpus.isEmpty()).isTrue();
        assertThat(corpus.neverLoaded()).isFalse();
        verify(components, never()).findAllByIdNotNull(any(Pageable.class));
    }

    @Test
    @DisplayName("the scan stops at its cap and says the corpus is partial")
    void truncates() {
        List<ComponentEntity> fullPage = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fullPage.add(component(PROXY_REPOSITORY, "package-" + i, "1.0.0"));
        }
        when(components.findAllByIdNotNull(any(Pageable.class))).thenReturn(fullPage);

        ComponentCorpusService service = service(new ComponentCorpusProperties(
                true, Duration.ofMinutes(30), 50, 1000L));
        service.runRefresh();

        assertThat(service.corpus().truncated()).isTrue();
        assertThat(service.corpus().scannedComponents()).isEqualTo(1000);
    }

    @Test
    @DisplayName("a component whose format has no purl mapper is skipped, not counted as a name")
    void unmappedFormat() {
        ComponentEntity raw = component(PROXY_REPOSITORY, "some-file.bin", "1");
        raw.setFormat("raw");
        givenComponents(raw, component(PROXY_REPOSITORY, "lodash", "1.0.0"));

        ComponentCorpusService service = service(ComponentCorpusProperties.defaults());
        service.runRefresh();

        assertThat(service.corpus().size()).isEqualTo(1);
        assertThat(service.corpus().scannedComponents()).isEqualTo(2);
    }

    private ComponentCorpusService service(ComponentCorpusProperties properties) {
        return new ComponentCorpusService(components, repositories, purlBuilder, properties);
    }

    private void givenComponents(ComponentEntity... entities) {
        when(components.findAllByIdNotNull(any(Pageable.class))).thenReturn(List.of(entities));
    }

    private static ComponentEntity component(UUID repositoryId, String name, String version) {
        ComponentEntity entity = new ComponentEntity();
        entity.setId(UUID.randomUUID());
        entity.setRepositoryId(repositoryId);
        entity.setFormat("npm");
        entity.setName(name);
        entity.setVersion(version);
        return entity;
    }

    private static RepositoryEntity repository(UUID id, String name, String type) {
        RepositoryEntity entity = new RepositoryEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setFormat("npm");
        entity.setType(type);
        return entity;
    }

    /**
     * A stand-in for the npm module's mapper. The corpus deliberately goes
     * through {@code PurlBuilder} rather than the raw component columns, and this
     * test module does not depend on the format modules — so the SPI is
     * exercised with the smallest possible implementation of it.
     */
    private static final class TestNpmPurlMapper implements PurlMapper {

        @Override
        public String format() {
            return "npm";
        }

        @Override
        public Optional<PackageURL> toPurl(ComponentEntity component) {
            if (!"npm".equals(component.getFormat())) {
                return Optional.empty();
            }
            return Optional.of(CorpusFixtures.purl(
                    "npm", component.getNamespace(), component.getName(), component.getVersion()));
        }
    }
}
