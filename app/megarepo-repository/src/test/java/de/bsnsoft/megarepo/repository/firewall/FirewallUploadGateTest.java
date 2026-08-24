package de.bsnsoft.megarepo.repository.firewall;

import de.bsnsoft.megarepo.core.firewall.FirewallAction;
import de.bsnsoft.megarepo.core.firewall.FirewallFailMode;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.entity.AssetEntity;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AssetJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The seam between the router's PUT and the upload evaluator.
 *
 * <p>Most of what is asserted here is what the gate does <em>not</em> do. It sits
 * on the publish path of every hosted repository in the installation, and the
 * promise it has to keep is that an instance which never switched enforcement on
 * pays nothing for it: no settings lookup, no asset query, no component query.
 * The other half is that nothing it can get wrong ever costs a developer their
 * release.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FirewallUploadGateTest {

    private static final UUID REPO_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMPONENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String PATH = "com/acme/util/1.0.0/util-1.0.0.jar";
    private static final String SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final FirewallRequestContext CONTEXT =
            new FirewallRequestContext("release-bot", "10.0.0.7", PATH, "PUT");

    @Mock private FirewallEnforcementSettingsService enforcementSettings;
    @Mock private FirewallEvaluationService evaluationService;
    @Mock private FirewallUploadEvaluator uploadEvaluator;
    @Mock private FirewallViolationRecorder recorder;
    @Mock private AssetJpaRepository assets;
    @Mock private ComponentJpaRepository components;

    // ---------------------------------------------------- costs nothing when off

    @Test
    @DisplayName("the master switch off costs a publish not one query")
    void masterSwitchOffQueriesNothing() {
        when(enforcementSettings.enforcementEnabled()).thenReturn(false);

        FirewallEvaluation verdict = gate().evaluate(hosted(), PATH, CONTEXT);

        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.outcome()).isEqualTo(FirewallEvaluation.Outcome.NOT_ENFORCING);
        verifyNoInteractions(evaluationService, assets, components, uploadEvaluator, recorder);
    }

    @Test
    @DisplayName("a proxy is never published into, so it is never even looked up")
    void nonHostedRepositoriesAreNotEvaluated() {
        when(enforcementSettings.enforcementEnabled()).thenReturn(true);

        assertThat(gate().evaluate(repository(RepositoryType.PROXY), PATH, CONTEXT).outcome())
                .isEqualTo(FirewallEvaluation.Outcome.NOT_ENFORCING);
        assertThat(gate().evaluate(repository(RepositoryType.GROUP), PATH, CONTEXT).outcome())
                .as("through a group it is the writable member that is judged, never the group")
                .isEqualTo(FirewallEvaluation.Outcome.NOT_ENFORCING);
        verifyNoInteractions(evaluationService, assets, components, uploadEvaluator);
    }

    @Test
    @DisplayName("a repository that is not in QUARANTINE stops after the settings lookup")
    void auditModeStopsBeforeTheAssetQuery() {
        givenEnforcementOn();
        when(evaluationService.resolveSettings(REPO_ID)).thenReturn(new FirewallRepositorySettings(
                FirewallMode.AUDIT, FirewallFailMode.FAIL_CLOSED, null, true));

        FirewallEvaluation verdict = gate().evaluate(hosted(), PATH, CONTEXT);

        assertThat(verdict.outcome()).isEqualTo(FirewallEvaluation.Outcome.NOT_ENFORCING);
        verifyNoInteractions(assets, components, uploadEvaluator);
    }

    @Test
    @DisplayName("nothing to judge without a repository or a path")
    void missingArgumentsAreNotEvaluated() {
        givenEnforcementOn();

        assertThat(gate().evaluate(null, PATH, CONTEXT).outcome())
                .isEqualTo(FirewallEvaluation.Outcome.NOT_ENFORCING);
        assertThat(gate().evaluate(hosted(), null, CONTEXT).outcome())
                .isEqualTo(FirewallEvaluation.Outcome.NOT_ENFORCING);
        verifyNoInteractions(uploadEvaluator);
    }

    // ------------------------------------------------------------ no component

    @Test
    @DisplayName("a path that carries no component is not refused — a deploy writes several")
    void aPathWithoutAComponentIsNotRefused() {
        givenEnforcing();
        when(assets.findByRepositoryIdAndPath(REPO_ID, PATH)).thenReturn(Optional.empty());

        FirewallEvaluation verdict = gate().evaluate(hosted(), PATH, CONTEXT);

        assertThat(verdict.blocked())
                .as("refusing on 'we could not identify it' would break every maven-metadata.xml")
                .isFalse();
        assertThat(verdict.outcome()).isEqualTo(FirewallEvaluation.Outcome.NOT_ENFORCING);
        verifyNoInteractions(uploadEvaluator);
    }

    @Test
    @DisplayName("an asset whose component row is gone is not refused either")
    void anAssetWithADanglingComponentIsNotRefused() {
        givenEnforcing();
        when(assets.findByRepositoryIdAndPath(REPO_ID, PATH)).thenReturn(Optional.of(asset()));
        when(components.findById(COMPONENT_ID)).thenReturn(Optional.empty());

        assertThat(gate().evaluate(hosted(), PATH, CONTEXT).blocked()).isFalse();
        verifyNoInteractions(uploadEvaluator);
    }

    // -------------------------------------------------------------- evaluation

    @Test
    @DisplayName("an identified upload is judged, with the checksum the asset row carries")
    void anIdentifiedUploadIsJudged() {
        givenEnforcing();
        givenStoredComponent();
        when(uploadEvaluator.evaluate(any(), anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(blocked());

        FirewallEvaluation verdict = gate().evaluate(hosted(), PATH, CONTEXT);

        assertThat(verdict.blocked()).isTrue();
        verify(uploadEvaluator).evaluate(
                eq(REPO_ID), eq("maven-releases"), eq(RepositoryType.HOSTED), eq(PATH),
                any(ComponentEntity.class), eq(SHA256), eq(CONTEXT));
    }

    @Test
    @DisplayName("an enforced publish is written to the violation log, refused or not")
    void anEnforcedUploadIsRecorded() {
        givenEnforcing();
        givenStoredComponent();
        when(uploadEvaluator.evaluate(any(), anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(blocked());

        gate().evaluate(hosted(), PATH, CONTEXT);

        verify(recorder).recordDecision(any(FirewallEvaluation.class), eq(CONTEXT));
    }

    @Test
    @DisplayName("a decision nobody took is not written — there is no verdict to record")
    void anUnenforcedUploadIsNotRecorded() {
        when(enforcementSettings.enforcementEnabled()).thenReturn(false);

        gate().evaluate(hosted(), PATH, CONTEXT);

        verify(recorder, never()).recordDecision(any(), any());
    }

    @Test
    @DisplayName("a recorder that throws does not turn a refusal into an acceptance")
    void aFailedRecordDoesNotChangeTheVerdict() {
        givenEnforcing();
        givenStoredComponent();
        when(uploadEvaluator.evaluate(any(), anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(blocked());
        when(recorder.recordDecision(any(), any()))
                .thenThrow(new IllegalStateException("constraint violation"));

        assertThat(gate().evaluate(hosted(), PATH, CONTEXT).blocked()).isTrue();
    }

    // ------------------------------------------------------------------ faults

    @Test
    @DisplayName("a firewall fault keeps the upload, the way a firewall fault serves a download")
    void aDefectKeepsTheUpload() {
        givenEnforcementOn();
        when(evaluationService.resolveSettings(REPO_ID))
                .thenThrow(new IllegalStateException("config table unreachable"));

        FirewallEvaluation verdict = gate().evaluate(hosted(), PATH, CONTEXT);

        assertThat(verdict.blocked())
                .as("a firewall fault must cost a developer their release no more than a consumer an artifact")
                .isFalse();
        assertThat(verdict.outcome()).isEqualTo(FirewallEvaluation.Outcome.FAILED);
    }

    @Test
    @DisplayName("an evaluator that throws keeps the upload as well")
    void aThrowingEvaluatorKeepsTheUpload() {
        givenEnforcing();
        givenStoredComponent();
        when(uploadEvaluator.evaluate(any(), anyString(), any(), anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("the policy engine is broken"));

        FirewallEvaluation verdict = gate().evaluate(hosted(), PATH, CONTEXT);

        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.outcome()).isEqualTo(FirewallEvaluation.Outcome.FAILED);
    }

    @Test
    @DisplayName("an asset store that throws keeps the upload too")
    void aThrowingAssetStoreKeepsTheUpload() {
        givenEnforcing();
        when(assets.findByRepositoryIdAndPath(any(), anyString()))
                .thenThrow(new IllegalStateException("asset table unreachable"));

        assertThat(gate().evaluate(hosted(), PATH, CONTEXT).blocked()).isFalse();
    }

    // ------------------------------------------------------------------

    private FirewallUploadGate gate() {
        return new FirewallUploadGate(
                enforcementSettings, evaluationService, uploadEvaluator, recorder, assets, components);
    }

    private void givenEnforcementOn() {
        when(enforcementSettings.enforcementEnabled()).thenReturn(true);
    }

    private void givenEnforcing() {
        givenEnforcementOn();
        when(evaluationService.resolveSettings(REPO_ID)).thenReturn(new FirewallRepositorySettings(
                FirewallMode.QUARANTINE, FirewallFailMode.FAIL_OPEN, null, true));
    }

    private void givenStoredComponent() {
        when(assets.findByRepositoryIdAndPath(REPO_ID, PATH)).thenReturn(Optional.of(asset()));
        when(components.findById(COMPONENT_ID)).thenReturn(Optional.of(new ComponentEntity()));
    }

    private static AssetEntity asset() {
        AssetEntity asset = new AssetEntity();
        asset.setComponentId(COMPONENT_ID);
        asset.setChecksumSha256(SHA256);
        return asset;
    }

    private static RepositoryConfig hosted() {
        return repository(RepositoryType.HOSTED);
    }

    private static RepositoryConfig repository(RepositoryType type) {
        return new RepositoryConfig(
                REPO_ID, "maven-releases", "maven2", type, true, "default", Map.of());
    }

    private static FirewallEvaluation blocked() {
        return new FirewallEvaluation(
                REPO_ID, "maven-releases", PATH,
                new FirewallRepositorySettings(
                        FirewallMode.QUARANTINE, FirewallFailMode.FAIL_OPEN, null, true),
                null, List.of(), FirewallEvaluation.Outcome.MATCHED, false,
                FirewallDecision.blocked(null, "Default", List.of(new FirewallRuleViolation(
                        FirewallRuleType.KNOWN_MALICIOUS, FirewallAction.BLOCK,
                        "advisory MAL-2024-1234 flags this component as malicious",
                        List.of("MAL-2024-1234")))));
    }
}
