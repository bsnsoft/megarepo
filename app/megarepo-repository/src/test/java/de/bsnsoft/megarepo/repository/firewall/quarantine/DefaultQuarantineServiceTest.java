package de.bsnsoft.megarepo.repository.firewall.quarantine;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.firewall.FirewallMode;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineReason;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineResolution;
import de.bsnsoft.megarepo.core.firewall.FirewallQuarantineState;
import de.bsnsoft.megarepo.database.entity.FirewallQuarantineEntity;
import de.bsnsoft.megarepo.database.repository.FirewallQuarantineJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.FirewallDecision;
import de.bsnsoft.megarepo.repository.firewall.FirewallEvaluation;
import de.bsnsoft.megarepo.repository.firewall.FirewallRepositorySettings;
import de.bsnsoft.megarepo.repository.firewall.FirewallRequestContext;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The state machine, without a database.
 *
 * <p>The transitions, the two ways nothing happens (quarantine off,
 * pre-existing component) and the refusal of an illegal transition are decisions
 * this class makes on its own — the database only stores what it decided. A
 * container would slow these down without asserting anything more.
 *
 * <p>What is <em>not</em> here: whether the due-list query returns the right
 * rows, and whether the hit counter really increments without a load. Both are
 * properties of SQL and are asserted in {@code FirewallQuarantineDatabaseTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultQuarantineServiceTest {

    private static final UUID REPOSITORY = UUID.randomUUID();
    private static final String REPOSITORY_NAME = "maven-hosted";
    private static final String KEY = "pkg:maven/com.acme/util@1.0.0";
    private static final String PATH = "com/acme/util/1.0.0/util-1.0.0.jar";
    private static final FirewallRequestContext CONTEXT =
            new FirewallRequestContext("ci-build", "10.0.0.7", PATH, "GET");

    @Mock private FirewallQuarantineJpaRepository entries;
    @Mock private QuarantineReevaluator reevaluator;

    private DefaultQuarantineService service;

    @BeforeEach
    void setUp() {
        when(entries.save(any(FirewallQuarantineEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = service(QuarantineProperties.defaults());
    }

    private DefaultQuarantineService service(QuarantineProperties properties) {
        return new DefaultQuarantineService(
                entries, new QuarantineMapper(), reevaluator, properties);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("holding a component")
    class Holding {

        @Test
        @DisplayName("a new component is held, keyed on the identity and due at once")
        void holdsANewComponent() {
            when(entries.findByRepositoryIdAndComponentKey(REPOSITORY, KEY))
                    .thenReturn(Optional.empty());

            Optional<FirewallQuarantineEntry> held = service.quarantine(
                    evaluation(false), FirewallQuarantineReason.MIN_AGE_NOT_MET, CONTEXT);

            assertThat(held).isPresent();
            assertThat(held.get().state()).isEqualTo(FirewallQuarantineState.QUARANTINED);
            assertThat(held.get().reason()).isEqualTo(FirewallQuarantineReason.MIN_AGE_NOT_MET);
            assertThat(held.get().componentKey()).isEqualTo(KEY);
            assertThat(held.get().hitCount()).isEqualTo(1);
            assertThat(held.get().nextEvaluationAt())
                    .as("a fresh entry has no backoff history — the first sweep should look")
                    .isNotNull();
            assertThat(held.get().denies()).isTrue();
        }

        @Test
        @DisplayName("the snapshot records the rules and the request that tripped it")
        void snapshotCarriesTheDecision() {
            when(entries.findByRepositoryIdAndComponentKey(REPOSITORY, KEY))
                    .thenReturn(Optional.empty());

            FirewallQuarantineEntry held = service.quarantine(
                    evaluation(false), FirewallQuarantineReason.UNKNOWN_COMPONENT, CONTEXT)
                    .orElseThrow();

            assertThat(held.evaluation()).containsKey("request");
            assertThat(held.evaluation()).containsEntry("componentKey", KEY);
        }

        @Test
        @DisplayName("A COMPONENT THAT WAS ALREADY THERE IS NEVER HELD")
        void preExistingIsNeverHeld() {
            Optional<FirewallQuarantineEntry> held = service.quarantine(
                    evaluation(true), FirewallQuarantineReason.MIN_AGE_NOT_MET, CONTEXT);

            assertThat(held)
                    .as("switching enforcement on may not break a build that worked yesterday")
                    .isEmpty();
            verify(entries, never()).save(any());
            verify(entries, never()).findByRepositoryIdAndComponentKey(any(), any());
        }

        @Test
        @DisplayName("POLICY_VIOLATION is a way out of quarantine, never a way in")
        void policyViolationIsNotAnEntryReason() {
            Optional<FirewallQuarantineEntry> held = service.quarantine(
                    evaluation(false), FirewallQuarantineReason.POLICY_VIOLATION, CONTEXT);

            assertThat(held).isEmpty();
            verify(entries, never()).save(any());
        }

        @Test
        @DisplayName("a component with no usable key produces no entry")
        void unkeyableComponentIsNotHeld() {
            FirewallEvaluation withoutIdentity = new FirewallEvaluation(
                    REPOSITORY, REPOSITORY_NAME, PATH, settings(), null, List.of(),
                    FirewallEvaluation.Outcome.MATCHED, false, FirewallDecision.allowed());

            assertThat(service.quarantine(
                    withoutIdentity, FirewallQuarantineReason.UNKNOWN_COMPONENT, CONTEXT))
                    .isEmpty();
            verify(entries, never()).save(any());
        }

        @Test
        @DisplayName("a component already held is refreshed, not duplicated")
        void repeatedHoldRefreshes() {
            FirewallQuarantineEntity existing = held();
            existing.setHitCount(4);
            when(entries.findByRepositoryIdAndComponentKey(REPOSITORY, KEY))
                    .thenReturn(Optional.of(existing));

            FirewallQuarantineEntry refreshed = service.quarantine(
                    evaluation(false), FirewallQuarantineReason.MIN_AGE_NOT_MET, CONTEXT)
                    .orElseThrow();

            assertThat(refreshed.hitCount()).isEqualTo(5);
            assertThat(refreshed.id()).isEqualTo(existing.getId());
        }

        @Test
        @DisplayName("a released component is not held again on the next download")
        void aReleasedComponentIsNotResurrected() {
            FirewallQuarantineEntity released = held();
            released.setState(FirewallQuarantineState.RELEASED);
            released.setResolution(FirewallQuarantineResolution.MANUAL_RELEASE);
            when(entries.findByRepositoryIdAndComponentKey(REPOSITORY, KEY))
                    .thenReturn(Optional.of(released));

            assertThat(service.quarantine(
                    evaluation(false), FirewallQuarantineReason.MIN_AGE_NOT_MET, CONTEXT))
                    .as("re-holding here would undo an operator's release on the next request")
                    .isEmpty();
            assertThat(released.getState()).isEqualTo(FirewallQuarantineState.RELEASED);
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("switched off")
    class SwitchedOff {

        private DefaultQuarantineService off;

        @BeforeEach
        void setUp() {
            off = service(QuarantineProperties.disabled());
        }

        @Test
        @DisplayName("quarantine() writes nothing")
        void writesNothing() {
            assertThat(off.quarantine(
                    evaluation(false), FirewallQuarantineReason.MIN_AGE_NOT_MET, CONTEXT))
                    .isEmpty();
            verifyNoInteractions(entries);
        }

        @Test
        @DisplayName("find() finds nothing, so the policy alone decides")
        void findsNothing() {
            assertThat(off.find(REPOSITORY, KEY)).isEmpty();
            verifyNoInteractions(entries);
        }

        @Test
        @DisplayName("existing rows are left alone — a disable is not a data migration")
        void existingRowsAreUntouched() {
            off.recordHit(UUID.randomUUID(), Instant.now());
            assertThat(off.reevaluateDue(Instant.now(), 100)).isZero();

            verifyNoInteractions(entries);
            verifyNoInteractions(reevaluator);
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("transitions")
    class Transitions {

        @Test
        @DisplayName("QUARANTINED -> RELEASED records resolution, who and when")
        void releaseFromHeld() {
            FirewallQuarantineEntity entity = givenEntry(FirewallQuarantineState.QUARANTINED);

            FirewallQuarantineEntry released = service.release(entity.getId(),
                    QuarantineDecision.manual(
                            FirewallQuarantineResolution.MANUAL_RELEASE, "alice", "reviewed by hand"));

            assertThat(released.state()).isEqualTo(FirewallQuarantineState.RELEASED);
            assertThat(released.resolution())
                    .isEqualTo(FirewallQuarantineResolution.MANUAL_RELEASE);
            assertThat(released.decidedBy()).isEqualTo("alice");
            assertThat(released.decisionReason()).isEqualTo("reviewed by hand");
            assertThat(released.decidedAt()).isNotNull();
            assertThat(released.nextEvaluationAt())
                    .as("a decided entry must leave the sweep's index")
                    .isNull();
            assertThat(released.denies()).isFalse();
        }

        @Test
        @DisplayName("QUARANTINED -> BLOCKED")
        void blockFromHeld() {
            FirewallQuarantineEntity entity = givenEntry(FirewallQuarantineState.QUARANTINED);

            FirewallQuarantineEntry blocked = service.block(entity.getId(),
                    QuarantineDecision.manual(
                            FirewallQuarantineResolution.MANUAL_BLOCK, "alice", "not acceptable"));

            assertThat(blocked.state()).isEqualTo(FirewallQuarantineState.BLOCKED);
            assertThat(blocked.denies()).isTrue();
            assertThat(blocked.held()).isFalse();
        }

        @Test
        @DisplayName("an operator may release something they blocked by mistake")
        void deliberateReDecisionIsAllowed() {
            FirewallQuarantineEntity entity = givenEntry(FirewallQuarantineState.BLOCKED);

            assertThatCode(() -> service.release(entity.getId(),
                    QuarantineDecision.manual(
                            FirewallQuarantineResolution.MANUAL_RELEASE, "alice", "my mistake")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an approved exemption may release a blocked entry")
        void exemptionReDecisionIsAllowed() {
            FirewallQuarantineEntity entity = givenEntry(FirewallQuarantineState.BLOCKED);
            UUID exemption = UUID.randomUUID();

            FirewallQuarantineEntry released = service.release(entity.getId(),
                    QuarantineDecision.byExemption(exemption, "alice", "signed off"));

            assertThat(released.exemptionId()).isEqualTo(exemption);
        }

        @Test
        @DisplayName("releasing an already-released entry throws rather than being absorbed")
        void doubleReleaseThrows() {
            FirewallQuarantineEntity entity = givenEntry(FirewallQuarantineState.RELEASED);

            assertThatThrownBy(() -> service.release(entity.getId(),
                    QuarantineDecision.manual(
                            FirewallQuarantineResolution.MANUAL_RELEASE, "alice", null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("RELEASED");
        }

        @Test
        @DisplayName("blocking an already-blocked entry throws")
        void doubleBlockThrows() {
            FirewallQuarantineEntity entity = givenEntry(FirewallQuarantineState.BLOCKED);

            assertThatThrownBy(() -> service.block(entity.getId(),
                    QuarantineDecision.manual(
                            FirewallQuarantineResolution.MANUAL_BLOCK, "alice", null)))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("the sweep may not re-decide an entry somebody already decided")
        void automaticReDecisionThrows() {
            FirewallQuarantineEntity entity = givenEntry(FirewallQuarantineState.BLOCKED);

            assertThatThrownBy(() -> service.release(entity.getId(),
                    QuarantineDecision.automatic(
                            FirewallQuarantineResolution.AGE_REACHED, "old enough now")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("deliberate");
        }

        @Test
        @DisplayName("a blocking resolution cannot be written onto a release")
        void resolutionMustMatchTheTransition() {
            FirewallQuarantineEntity entity = givenEntry(FirewallQuarantineState.QUARANTINED);

            assertThatThrownBy(() -> service.release(entity.getId(),
                    QuarantineDecision.manual(
                            FirewallQuarantineResolution.MANUAL_BLOCK, "alice", null)))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> service.block(entity.getId(),
                    QuarantineDecision.manual(
                            FirewallQuarantineResolution.MANUAL_RELEASE, "alice", null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("deciding an entry that does not exist is not silently ignored")
        void unknownEntryThrows() {
            UUID missing = UUID.randomUUID();
            when(entries.findById(missing)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.release(missing,
                    QuarantineDecision.manual(
                            FirewallQuarantineResolution.MANUAL_RELEASE, "alice", null)))
                    .isInstanceOf(NoSuchElementException.class);
        }

        @Test
        @DisplayName("a re-evaluated violation is recorded as one")
        void reevaluatedViolationRewritesTheReason() {
            FirewallQuarantineEntity entity = givenEntry(FirewallQuarantineState.QUARANTINED);
            entity.setReasonCode(FirewallQuarantineReason.UNKNOWN_COMPONENT);

            service.block(entity.getId(), QuarantineDecision.automatic(
                    FirewallQuarantineResolution.POLICY_VIOLATION, "a critical advisory arrived"));

            assertThat(entity.getReasonCode())
                    .as("the row has to say why the answer changed")
                    .isEqualTo(FirewallQuarantineReason.POLICY_VIOLATION);
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("hits and sweeps")
    class HitsAndSweeps {

        @Test
        @DisplayName("recordHit updates the counter without loading the entity")
        void hitDoesNotLoadTheRow() {
            UUID id = UUID.randomUUID();
            Instant seenAt = Instant.parse("2026-08-24T10:15:30Z");

            service.recordHit(id, seenAt);

            verify(entries).recordHit(id, seenAt);
            verify(entries, never()).findById(any());
            verify(entries, never()).save(any());
        }

        @Test
        @DisplayName("a failing hit counter does not change an answer already given")
        void aFailingHitIsSwallowed() {
            UUID id = UUID.randomUUID();
            when(entries.recordHit(eq(id), any())).thenThrow(new IllegalStateException("gone"));

            assertThatCode(() -> service.recordHit(id, Instant.now())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a released entry counts as a change, a still-held one does not")
        void sweepCountsStateChanges() {
            FirewallQuarantineEntity releasable = held();
            FirewallQuarantineEntity staying = held();
            staying.setId(UUID.randomUUID());
            when(entries.findDueForReevaluation(any(), any()))
                    .thenReturn(List.of(releasable, staying));
            when(reevaluator.reevaluate(eq(releasable), any())).thenReturn(
                    new QuarantineReevaluator.Verdict(
                            QuarantineReevaluator.Outcome.RELEASE,
                            FirewallQuarantineResolution.AGE_REACHED,
                            "old enough", null, null));
            when(reevaluator.reevaluate(eq(staying), any())).thenReturn(
                    new QuarantineReevaluator.Verdict(
                            QuarantineReevaluator.Outcome.HOLD, null, "still too new", null,
                            Instant.now().plusSeconds(600)));

            assertThat(service.reevaluateDue(Instant.now(), 100)).isEqualTo(1);
            assertThat(releasable.getState()).isEqualTo(FirewallQuarantineState.RELEASED);
            assertThat(releasable.getResolution())
                    .isEqualTo(FirewallQuarantineResolution.AGE_REACHED);
            assertThat(releasable.getDecidedBy()).isEqualTo(QuarantineDecision.SYSTEM);
            assertThat(staying.getState()).isEqualTo(FirewallQuarantineState.QUARANTINED);
            assertThat(staying.getNextEvaluationAt()).isNotNull();
        }

        @Test
        @DisplayName("one entry that blows up does not cost the rest of the batch its releases")
        void oneBadEntryDoesNotStopTheSweep() {
            FirewallQuarantineEntity bad = held();
            FirewallQuarantineEntity good = held();
            good.setId(UUID.randomUUID());
            when(entries.findDueForReevaluation(any(), any())).thenReturn(List.of(bad, good));
            when(reevaluator.reevaluate(eq(bad), any())).thenThrow(new IllegalStateException("boom"));
            when(reevaluator.reevaluate(eq(good), any())).thenReturn(
                    new QuarantineReevaluator.Verdict(
                            QuarantineReevaluator.Outcome.RELEASE,
                            FirewallQuarantineResolution.RE_EVALUATED_CLEAN, "clean", null, null));

            assertThat(service.reevaluateDue(Instant.now(), 100)).isEqualTo(1);
        }

        @Test
        @DisplayName("invalidatePolicy makes held entries due now rather than deciding for the engine")
        void invalidatePolicySchedules() {
            UUID policy = UUID.randomUUID();
            FirewallQuarantineEntity entity = held();
            entity.setNextEvaluationAt(Instant.now().plusSeconds(3600));
            when(entries.findByPolicyIdAndState(policy, FirewallQuarantineState.QUARANTINED))
                    .thenReturn(List.of(entity));

            assertThat(service.invalidatePolicy(policy)).isEqualTo(1);
            assertThat(entity.getState()).isEqualTo(FirewallQuarantineState.QUARANTINED);
            assertThat(entity.getNextEvaluationAt()).isBeforeOrEqualTo(Instant.now());
        }

        @Test
        @DisplayName("summary counts every state")
        void summaryCounts() {
            when(entries.countByState(FirewallQuarantineState.QUARANTINED)).thenReturn(7L);
            when(entries.countByState(FirewallQuarantineState.RELEASED)).thenReturn(3L);
            when(entries.countByState(FirewallQuarantineState.BLOCKED)).thenReturn(1L);

            assertThat(service.summary())
                    .isEqualTo(new QuarantineService.QuarantineSummary(7, 3, 1));
        }

        @Test
        @DisplayName("an unreadable quarantine store answers 'not seen before', never 'refused'")
        void findNeverThrows() {
            when(entries.findByRepositoryIdAndComponentKey(any(), any()))
                    .thenThrow(new IllegalStateException("connection reset"));

            assertThat(service.find(REPOSITORY, KEY)).isEmpty();
        }
    }

    // ------------------------------------------------------------------

    private FirewallQuarantineEntity givenEntry(FirewallQuarantineState state) {
        FirewallQuarantineEntity entity = held();
        entity.setState(state);
        if (state != FirewallQuarantineState.QUARANTINED) {
            entity.setResolution(FirewallQuarantineResolution.MANUAL_BLOCK);
            entity.setDecidedAt(Instant.now());
            entity.setDecidedBy("bob");
        }
        when(entries.findById(entity.getId())).thenReturn(Optional.of(entity));
        return entity;
    }

    private static FirewallQuarantineEntity held() {
        FirewallQuarantineEntity entity = new FirewallQuarantineEntity();
        entity.setId(UUID.randomUUID());
        entity.setRepositoryId(REPOSITORY);
        entity.setRepositoryName(REPOSITORY_NAME);
        entity.setComponentKey(KEY);
        entity.setPath(PATH);
        entity.setState(FirewallQuarantineState.QUARANTINED);
        entity.setReasonCode(FirewallQuarantineReason.MIN_AGE_NOT_MET);
        return entity;
    }

    private static FirewallEvaluation evaluation(boolean preExisting) {
        return new FirewallEvaluation(
                REPOSITORY, REPOSITORY_NAME, PATH, settings(), identity(), List.of(),
                FirewallEvaluation.Outcome.MATCHED, preExisting,
                FirewallDecision.allowed(null, "Default", List.of()));
    }

    private static FirewallRepositorySettings settings() {
        return new FirewallRepositorySettings(FirewallMode.QUARANTINE, null, null, true);
    }

    private static ComponentIdentity identity() {
        try {
            return new ComponentIdentity.Purl(new PackageURL(KEY));
        } catch (MalformedPackageURLException e) {
            throw new IllegalStateException(e);
        }
    }
}
