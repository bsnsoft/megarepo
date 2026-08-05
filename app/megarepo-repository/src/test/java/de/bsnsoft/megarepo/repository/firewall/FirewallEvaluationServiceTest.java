package de.bsnsoft.megarepo.repository.firewall;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.entity.FirewallRepositoryConfigEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.FirewallRepositoryConfigJpaRepository;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryFinding;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryLookupService;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryMatch;
import de.bsnsoft.megarepo.repository.advisory.MatchConfidence;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlBuilder;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The AUDIT orchestration: identity, lookup, record — and the three things it
 * must never do, which is block, throw, or act on a mode it cannot honour.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FirewallEvaluationServiceTest {

    private static final UUID REPO_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMPONENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String PATH = "org/apache/logging/log4j/log4j-core/2.14.1/log4j-core-2.14.1.jar";
    private static final FirewallRequestContext CONTEXT =
            new FirewallRequestContext("ci-build", "10.0.0.7", PATH, "GET");

    @Mock private FirewallRepositoryConfigJpaRepository configs;
    @Mock private AssetJpaRepository assets;
    @Mock private ComponentJpaRepository components;
    @Mock private AdvisoryLookupService advisories;
    @Mock private FirewallViolationRecorder recorder;

    private FirewallEvaluationService service;

    @BeforeEach
    void setUp() {
        service = newService(FirewallAuditProperties.defaults());
    }

    private FirewallEvaluationService newService(FirewallAuditProperties properties) {
        return new FirewallEvaluationService(
                configs, assets, components, new PurlBuilder(List.of(new MavenTestMapper())),
                advisories, recorder, properties);
    }

    @Test
    @DisplayName("AUDIT records the findings and reports that nothing was blocked")
    void auditRecordsAndNeverBlocks() {
        givenMode(FirewallMode.AUDIT);
        givenMavenComponent();
        when(advisories.findAdvisories(any(ComponentIdentity.class))).thenReturn(List.of(finding("GHSA-jfh8")));
        when(recorder.record(any(), any(), any(), any(), any(), any())).thenReturn(true);

        FirewallEvaluation evaluation = service.evaluateDownload(REPO_ID, "maven-central", PATH, CONTEXT);

        assertThat(evaluation.outcome()).isEqualTo(FirewallEvaluation.Outcome.RECORDED);
        assertThat(evaluation.blocked()).as("Phase 1 is AUDIT: record, serve anyway").isFalse();
        assertThat(evaluation.identity())
                .isInstanceOf(ComponentIdentity.Purl.class)
                .extracting(ComponentIdentity::key)
                .isEqualTo("pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1");
        verify(recorder).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("OFF touches nothing at all — no asset lookup, no advisory query, no row")
    void offModeDoesNothing() {
        givenMode(FirewallMode.OFF);

        FirewallEvaluation evaluation = service.evaluateDownload(REPO_ID, "maven-central", PATH, CONTEXT);

        assertThat(evaluation.outcome()).isEqualTo(FirewallEvaluation.Outcome.MODE_OFF);
        verifyNoInteractions(assets, components, advisories, recorder);
    }

    @Test
    @DisplayName("a repository without a config row falls back to the configured default, which ships as OFF")
    void missingConfigFallsBackToDefaultMode() {
        when(configs.findById(REPO_ID)).thenReturn(Optional.empty());

        FirewallEvaluation evaluation = service.evaluateDownload(REPO_ID, "maven-central", PATH, CONTEXT);

        assertThat(evaluation.outcome()).isEqualTo(FirewallEvaluation.Outcome.MODE_OFF);
        assertThat(evaluation.settings().explicit()).isFalse();
        verifyNoInteractions(assets, components, advisories, recorder);
    }

    @Test
    @DisplayName("the default mode is configurable — AUDIT observes repositories that have no row")
    void defaultModeCanBeRaisedToAudit() {
        when(configs.findById(REPO_ID)).thenReturn(Optional.empty());
        givenMavenComponent();
        when(advisories.findAdvisories(any(ComponentIdentity.class))).thenReturn(List.of());
        service = newService(new FirewallAuditProperties(
                true, FirewallMode.AUDIT, 2, 500,
                Duration.ofHours(24), Duration.ofMinutes(10), 10_000));

        FirewallEvaluation evaluation = service.evaluateDownload(REPO_ID, "maven-central", PATH, CONTEXT);

        assertThat(evaluation.outcome()).isEqualTo(FirewallEvaluation.Outcome.CLEAN);
    }

    @Test
    @DisplayName("QUARANTINE is treated exactly like AUDIT — it records, and it does not block")
    void quarantineBehavesLikeAuditInPhaseOne() {
        givenMode(FirewallMode.QUARANTINE);
        givenMavenComponent();
        when(advisories.findAdvisories(any(ComponentIdentity.class))).thenReturn(List.of(finding("GHSA-jfh8")));
        when(recorder.record(any(), any(), any(), any(), any(), any())).thenReturn(true);

        FirewallEvaluation evaluation = service.evaluateDownload(REPO_ID, "maven-central", PATH, CONTEXT);

        assertThat(evaluation.outcome()).isEqualTo(FirewallEvaluation.Outcome.RECORDED);
        assertThat(evaluation.blocked())
                .as("enforcement is Phase 2; a QUARANTINE repository is observed, not quarantined")
                .isFalse();
        assertThat(evaluation.settings().enforcementDeferred()).isTrue();
    }

    @Test
    @DisplayName("FAIL_CLOSED is recorded but not honoured — it cannot start denying downloads here")
    void failClosedDoesNotDeny() {
        FirewallRepositoryConfigEntity config = config(FirewallMode.AUDIT);
        config.setFailMode(FirewallFailMode.FAIL_CLOSED);
        when(configs.findById(REPO_ID)).thenReturn(Optional.of(config));
        givenMavenComponent();
        when(advisories.findAdvisories(any(ComponentIdentity.class)))
                .thenThrow(new IllegalStateException("advisory store unavailable"));

        FirewallEvaluation evaluation = service.evaluateDownload(REPO_ID, "maven-central", PATH, CONTEXT);

        assertThat(evaluation.outcome()).isEqualTo(FirewallEvaluation.Outcome.FAILED);
        assertThat(evaluation.blocked()).isFalse();
    }

    @Test
    @DisplayName("the master switch short-circuits before any query")
    void disabledShortCircuits() {
        service = newService(new FirewallAuditProperties(
                false, FirewallMode.AUDIT, 2, 500,
                Duration.ofHours(24), Duration.ofMinutes(10), 10_000));

        FirewallEvaluation evaluation = service.evaluateDownload(REPO_ID, "maven-central", PATH, CONTEXT);

        assertThat(evaluation.outcome()).isEqualTo(FirewallEvaluation.Outcome.DISABLED);
        verifyNoInteractions(configs, assets, components, advisories, recorder);
    }

    @Test
    @DisplayName("a path with no component — checksum, metadata — is not a finding")
    void pathWithoutComponentIsNotAFinding() {
        givenMode(FirewallMode.AUDIT);
        when(assets.findByRepositoryIdAndPath(REPO_ID, PATH)).thenReturn(Optional.empty());

        FirewallEvaluation evaluation = service.evaluateDownload(REPO_ID, "maven-central", PATH, CONTEXT);

        assertThat(evaluation.outcome()).isEqualTo(FirewallEvaluation.Outcome.NO_COMPONENT);
        verifyNoInteractions(advisories, recorder);
    }

    @Test
    @DisplayName("a component that only has a hash identity is not looked up and not recorded")
    void hashIdentityIsNotLookedUp() {
        givenMode(FirewallMode.AUDIT);
        ComponentEntity raw = component("raw", null, "blob.bin", "1");
        AssetEntity asset = asset("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        when(assets.findByRepositoryIdAndPath(REPO_ID, PATH)).thenReturn(Optional.of(asset));
        when(components.findById(COMPONENT_ID)).thenReturn(Optional.of(raw));

        FirewallEvaluation evaluation = service.evaluateDownload(REPO_ID, "raw-hosted", PATH, CONTEXT);

        assertThat(evaluation.outcome()).isEqualTo(FirewallEvaluation.Outcome.UNRESOLVABLE_IDENTITY);
        assertThat(evaluation.identity()).isInstanceOf(ComponentIdentity.Hash.class);
        verifyNoInteractions(advisories);
        verify(recorder, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a clean component is not recorded")
    void cleanComponentIsNotRecorded() {
        givenMode(FirewallMode.AUDIT);
        givenMavenComponent();
        when(advisories.findAdvisories(any(ComponentIdentity.class))).thenReturn(List.of());

        FirewallEvaluation evaluation = service.evaluateDownload(REPO_ID, "maven-central", PATH, CONTEXT);

        assertThat(evaluation.outcome()).isEqualTo(FirewallEvaluation.Outcome.CLEAN);
        verify(recorder, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a suppressed repeat is reported as such, not as a new record")
    void suppressedRepeatIsReported() {
        givenMode(FirewallMode.AUDIT);
        givenMavenComponent();
        when(advisories.findAdvisories(any(ComponentIdentity.class))).thenReturn(List.of(finding("GHSA-jfh8")));
        when(recorder.record(any(), any(), any(), any(), any(), any())).thenReturn(false);

        FirewallEvaluation evaluation = service.evaluateDownload(REPO_ID, "maven-central", PATH, CONTEXT);

        assertThat(evaluation.outcome()).isEqualTo(FirewallEvaluation.Outcome.SUPPRESSED);
        assertThat(evaluation.blocked()).isFalse();
    }

    @Test
    @DisplayName("every collaborator may explode; the evaluation still returns")
    void anyFailureIsSwallowed() {
        when(configs.findById(REPO_ID)).thenThrow(new RuntimeException("connection pool exhausted"));

        assertThatCode(() -> {
            FirewallEvaluation evaluation = service.evaluateDownload(REPO_ID, "maven-central", PATH, CONTEXT);
            assertThat(evaluation.outcome()).isEqualTo(FirewallEvaluation.Outcome.FAILED);
            assertThat(evaluation.blocked()).isFalse();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a recorder that throws does not escape")
    void recorderFailureIsSwallowed() {
        givenMode(FirewallMode.AUDIT);
        givenMavenComponent();
        when(advisories.findAdvisories(any(ComponentIdentity.class))).thenReturn(List.of(finding("GHSA-jfh8")));
        when(recorder.record(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("constraint violation"));

        FirewallEvaluation evaluation = service.evaluateDownload(REPO_ID, "maven-central", PATH, CONTEXT);

        assertThat(evaluation.outcome()).isEqualTo(FirewallEvaluation.Outcome.FAILED);
    }

    private void givenMode(FirewallMode mode) {
        when(configs.findById(REPO_ID)).thenReturn(Optional.of(config(mode)));
    }

    private static FirewallRepositoryConfigEntity config(FirewallMode mode) {
        FirewallRepositoryConfigEntity entity = new FirewallRepositoryConfigEntity();
        entity.setRepositoryId(REPO_ID);
        entity.setMode(mode);
        entity.setFailMode(FirewallFailMode.FAIL_OPEN);
        return entity;
    }

    private void givenMavenComponent() {
        when(assets.findByRepositoryIdAndPath(REPO_ID, PATH)).thenReturn(Optional.of(asset("abc")));
        when(components.findById(COMPONENT_ID))
                .thenReturn(Optional.of(component(
                        "maven2", "org.apache.logging.log4j", "log4j-core", "2.14.1")));
    }

    private static AssetEntity asset(String sha256) {
        AssetEntity asset = new AssetEntity();
        asset.setRepositoryId(REPO_ID);
        asset.setComponentId(COMPONENT_ID);
        asset.setPath(PATH);
        asset.setChecksumSha256(sha256);
        return asset;
    }

    private static ComponentEntity component(
            String format, String namespace, String name, String version) {
        ComponentEntity component = new ComponentEntity();
        component.setId(COMPONENT_ID);
        component.setRepositoryId(REPO_ID);
        component.setFormat(format);
        component.setNamespace(namespace);
        component.setName(name);
        component.setVersion(version);
        return component;
    }

    private static AdvisoryFinding finding(String id) {
        return new AdvisoryFinding(
                id, "RCE", "CRITICAL", 10.0, null, null, null,
                List.of(new AdvisoryMatch(id, "GHSA", MatchConfidence.EXACT, ">=2.0, <2.15.0")));
    }

    /**
     * Stands in for the Maven module's mapper, which lives in
     * megarepo-format-maven and is not on this module's test classpath.
     */
    private static final class MavenTestMapper implements PurlMapper {

        @Override
        public String format() {
            return "maven2";
        }

        @Override
        public Optional<PackageURL> toPurl(ComponentEntity component) {
            try {
                return Optional.of(new PackageURL(
                        "maven",
                        component.getNamespace(),
                        component.getName(),
                        component.getVersion(),
                        null,
                        null));
            } catch (Exception e) {
                return Optional.empty();
            }
        }
    }
}
