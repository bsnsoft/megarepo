package de.bsnsoft.megarepo.repository.firewall.exemption;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.core.firewall.FirewallComponentKeyKind;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionScope;
import de.bsnsoft.megarepo.core.firewall.FirewallExemptionState;
import de.bsnsoft.megarepo.core.firewall.FirewallRuleType;
import de.bsnsoft.megarepo.database.entity.FirewallExemptionEntity;
import de.bsnsoft.megarepo.database.repository.FirewallExemptionJpaRepository;
import de.bsnsoft.megarepo.repository.firewall.identity.ComponentIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The workflow's rules, without a database.
 *
 * <p>The state machine, the expiry arithmetic and the "never throws on the
 * request path" promise are decisions this class makes, so they are asserted
 * against a mocked store. What the store itself does — the partial index, the
 * {@code IN} over four key forms, the real migrated rows — is asserted in
 * {@link ExemptionDatabaseTest}, where mocks would only assert the mock.
 */
class DefaultExemptionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final UUID REPOSITORY = UUID.randomUUID();
    private static final String PURL = "pkg:maven/com.acme/util@1.0.0";

    private FirewallExemptionJpaRepository store;
    private DefaultExemptionService service;

    @BeforeEach
    void setUp() {
        store = mock(FirewallExemptionJpaRepository.class);
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(store.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(store.countByKeyKind(any())).thenReturn(0L);
        service = new DefaultExemptionService(
                store, ExemptionProperties.defaults(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ── Requesting ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("request")
    class Requesting {

        @Test
        @DisplayName("creates a REQUESTED exemption that lets nothing through yet")
        void createsRequested() {
            FirewallExemption created = service.request(request(FirewallExemptionScope.VERSION, null));

            assertThat(created.state()).isEqualTo(FirewallExemptionState.REQUESTED);
            assertThat(created.state().grantsPassage()).isFalse();
            assertThat(created.componentKey()).isEqualTo(PURL);
            assertThat(created.keyKind())
                    .as("only migration V18 writes a legacy coordinate")
                    .isEqualTo(FirewallComponentKeyKind.PURL);
            assertThat(created.expiresAt())
                    .as("the requester suggests; the approver decides")
                    .isNull();
            assertThat(created.requestedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("a COMPONENT-scoped request is stored under the version-less key")
        void componentScopeIsNormalised() {
            FirewallExemption created = service.request(request(FirewallExemptionScope.COMPONENT, null));

            assertThat(created.componentKey())
                    .as("stored version-bearing, a COMPONENT row would match nothing at all")
                    .isEqualTo("pkg:maven/com.acme/util");
        }

        @Test
        @DisplayName("the suggested expiry is recorded in the requester's own words")
        void suggestedExpiryIsKept() {
            Instant suggestion = NOW.plus(Duration.ofDays(30));

            FirewallExemption created =
                    service.request(request(FirewallExemptionScope.VERSION, suggestion));

            assertThat(created.justification()).contains(suggestion.toString());
            assertThat(created.expiresAt()).isNull();
        }

        @Test
        @DisplayName("an unexplained request is refused — the V8 list's defining flaw")
        void justificationIsRequired() {
            assertThatThrownBy(() -> new ExemptionRequest(
                            PURL, FirewallExemptionScope.VERSION, null, null, List.of(), null, "  ", "dev"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("justification");
        }

        @Test
        @DisplayName("a request nobody signed is refused")
        void requesterIsRequired() {
            assertThatThrownBy(() -> new ExemptionRequest(
                            PURL, FirewallExemptionScope.VERSION, null, null, List.of(), null, "why", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requestedBy");
        }

        @Test
        @DisplayName("a suggested expiry beyond max-validity is a typo, not a decision")
        void suggestedExpiryIsBounded() {
            Instant tooFar = NOW.plus(Duration.ofDays(4000));

            assertThatThrownBy(() -> service.request(request(FirewallExemptionScope.VERSION, tooFar)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("max-validity");
            verify(store, never()).save(any());
        }
    }

    // ── Deciding ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("state machine")
    class StateMachine {

        @Test
        @DisplayName("approve: REQUESTED becomes APPROVED, signed and dated")
        void approveFromRequested() {
            UUID id = given(FirewallExemptionState.REQUESTED);
            Instant expiry = NOW.plus(Duration.ofDays(30));

            FirewallExemption approved = service.approve(id, "ops", "checked the CVE", expiry);

            assertThat(approved.state()).isEqualTo(FirewallExemptionState.APPROVED);
            assertThat(approved.approvedBy()).isEqualTo("ops");
            assertThat(approved.approvedAt()).isEqualTo(NOW);
            assertThat(approved.expiresAt()).isEqualTo(expiry);
            assertThat(approved.decisionNote()).isEqualTo("checked the CVE");
            assertThat(approved.isLiveAt(NOW)).isTrue();
        }

        @Test
        @DisplayName("approve: a null expiry is allowed, because it was chosen")
        void approveForever() {
            UUID id = given(FirewallExemptionState.REQUESTED);

            FirewallExemption approved = service.approve(id, "ops", null, null);

            assertThat(approved.isPermanent()).isTrue();
            assertThat(approved.isLiveAt(NOW.plus(Duration.ofDays(10_000)))).isTrue();
        }

        @Test
        @DisplayName("approve: an expiry in the past would create an exemption that never applies")
        void approveInThePast() {
            UUID id = given(FirewallExemptionState.REQUESTED);

            assertThatThrownBy(() -> service.approve(id, "ops", null, NOW.minusSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(store, never()).save(any());
        }

        @Test
        @DisplayName("approve: beyond max-validity is refused, 'never' is still available")
        void approveBeyondCeiling() {
            UUID id = given(FirewallExemptionState.REQUESTED);

            assertThatThrownBy(() -> service.approve(id, "ops", null, NOW.plus(Duration.ofDays(4000))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("never");
        }

        @Test
        @DisplayName("approve: an exemption nobody signs is refused")
        void approveNeedsAnApprover() {
            UUID id = given(FirewallExemptionState.REQUESTED);

            assertThatThrownBy(() -> service.approve(id, " ", null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("approve: an already-approved exemption is not re-approved silently")
        void approveFromApproved() {
            UUID id = given(FirewallExemptionState.APPROVED);

            assertThatThrownBy(() -> service.approve(id, "ops", null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("APPROVED");
            verify(store, never()).save(any());
        }

        @Test
        @DisplayName("reject: only from REQUESTED")
        void reject() {
            UUID pending = given(FirewallExemptionState.REQUESTED);
            assertThat(service.reject(pending, "ops", "use 2.0 instead").state())
                    .isEqualTo(FirewallExemptionState.REJECTED);

            UUID live = given(FirewallExemptionState.APPROVED);
            assertThatThrownBy(() -> service.reject(live, "ops", null))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("revoke: only from APPROVED, and the expiry is not backdated")
        void revoke() {
            Instant expiry = NOW.plus(Duration.ofDays(30));
            UUID live = given(FirewallExemptionState.APPROVED, expiry);

            FirewallExemption revoked = service.revoke(live, "ops", "supplier compromised");

            assertThat(revoked.state()).isEqualTo(FirewallExemptionState.REVOKED);
            assertThat(revoked.expiresAt())
                    .as("a revoked exemption must not claim it lapsed by itself")
                    .isEqualTo(expiry);
            assertThat(revoked.isLiveAt(NOW)).isFalse();

            UUID pending = given(FirewallExemptionState.REQUESTED);
            assertThatThrownBy(() -> service.revoke(pending, "ops", null))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a decision on an exemption that is not there is a 404, not a new row")
        void unknownId() {
            UUID missing = UUID.randomUUID();
            when(store.findById(missing)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.approve(missing, "ops", null, null))
                    .isInstanceOf(NotFoundException.class);
            verify(store, never()).save(any());
        }
    }

    // ── Request path ────────────────────────────────────────────────────

    @Nested
    @DisplayName("findApplicable")
    class Applicability {

        @Test
        @DisplayName("an expired exemption blocks again, before any sweep has run")
        void expiredDoesNotApply() throws Exception {
            FirewallExemptionEntity lapsed = entity(FirewallExemptionState.APPROVED, NOW.minusSeconds(1));
            when(store.findApplicable(anyCollection(), eq(REPOSITORY), any())).thenReturn(List.of(lapsed));

            assertThat(service.findApplicable(REPOSITORY, identity(), NOW))
                    .as("the row still says APPROVED; the clock says otherwise and wins")
                    .isEmpty();
        }

        @Test
        @DisplayName("a live exemption applies")
        void liveApplies() throws Exception {
            FirewallExemptionEntity live =
                    entity(FirewallExemptionState.APPROVED, NOW.plus(Duration.ofDays(1)));
            when(store.findApplicable(anyCollection(), eq(REPOSITORY), any())).thenReturn(List.of(live));

            assertThat(service.findApplicable(REPOSITORY, identity(), NOW)).hasSize(1);
        }

        @Test
        @DisplayName("a rule-scoped exemption suppresses its rule and no other")
        void ruleScoped() throws Exception {
            FirewallExemptionEntity minAge = entity(FirewallExemptionState.APPROVED, null);
            minAge.setRuleType(FirewallRuleType.MIN_AGE);
            when(store.findApplicable(anyCollection(), eq(REPOSITORY), any())).thenReturn(List.of(minAge));

            assertThat(service.findApplicable(REPOSITORY, identity(), FirewallRuleType.MIN_AGE, NOW))
                    .isPresent();
            assertThat(service.findApplicable(
                            REPOSITORY, identity(), FirewallRuleType.KNOWN_MALICIOUS, NOW))
                    .as("exempt from MIN_AGE is not exempt from a malicious-package finding")
                    .isEmpty();
        }

        @Test
        @DisplayName("the narrowest exemption is reported first, so the log names the real decision")
        void narrowestFirst() throws Exception {
            FirewallExemptionEntity blanket = entity(FirewallExemptionState.APPROVED, null);
            blanket.setRepositoryId(null);
            blanket.setRuleType(null);
            FirewallExemptionEntity narrow = entity(FirewallExemptionState.APPROVED, null);
            narrow.setRepositoryId(REPOSITORY);
            narrow.setRuleType(FirewallRuleType.MIN_AGE);
            when(store.findApplicable(anyCollection(), eq(REPOSITORY), any()))
                    .thenReturn(List.of(blanket, narrow));

            List<FirewallExemption> applicable = service.findApplicable(REPOSITORY, identity(), NOW);

            assertThat(applicable).extracting(FirewallExemption::ruleType)
                    .containsExactly(FirewallRuleType.MIN_AGE, null);
        }

        @Test
        @DisplayName("a store that cannot be read denies rather than explodes")
        void storeFailureIsNotAnException() throws Exception {
            when(store.findApplicable(anyCollection(), any(), any()))
                    .thenThrow(new IllegalStateException("connection pool exhausted"));

            assertThat(service.findApplicable(REPOSITORY, identity(), NOW))
                    .as("an exception here would take the whole evaluation with it")
                    .isEmpty();
        }

        @Test
        @DisplayName("a component nothing can name asks the store nothing")
        void unnameableComponent() {
            assertThat(service.findApplicable(REPOSITORY, null, NOW)).isEmpty();
            verify(store, never()).findApplicable(anyCollection(), any(), any());
        }

        @Test
        @DisplayName("the legacy probe is not repeated on every download")
        void legacyProbeIsCached() throws Exception {
            when(store.findApplicable(anyCollection(), any(), any())).thenReturn(List.of());

            for (int i = 0; i < 10; i++) {
                service.findApplicable(REPOSITORY, identity(), NOW);
            }

            verify(store, times(1)).countByKeyKind(FirewallComponentKeyKind.LEGACY_COORDINATE);
        }
    }

    // ── Sweeps ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sweeps")
    class Sweeps {

        @Test
        @DisplayName("expireLapsed flips APPROVED rows whose date has passed")
        void expire() {
            FirewallExemptionEntity lapsed = entity(FirewallExemptionState.APPROVED, NOW.minusSeconds(1));
            when(store.findExpired(NOW)).thenReturn(List.of(lapsed));

            assertThat(service.expireLapsed(NOW)).isEqualTo(1);
            assertThat(lapsed.getState()).isEqualTo(FirewallExemptionState.EXPIRED);
        }

        @Test
        @DisplayName("expireLapsed writes nothing when nothing lapsed")
        void expireNothing() {
            when(store.findExpired(NOW)).thenReturn(List.of());

            assertThat(service.expireLapsed(NOW)).isZero();
            verify(store, never()).saveAll(any());
        }

        @Test
        @DisplayName("the notice stamps the row, which is what makes it fire once")
        void notice() {
            FirewallExemptionEntity soon =
                    entity(FirewallExemptionState.APPROVED, NOW.plus(Duration.ofDays(3)));
            when(store.findDueForExpiryNotice(NOW, NOW.plus(Duration.ofDays(7))))
                    .thenReturn(List.of(soon));

            List<FirewallExemption> announced = service.notifyUpcomingExpiry(NOW, Duration.ofDays(7));

            assertThat(announced).hasSize(1);
            assertThat(soon.getExpiryNotifiedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("summary counts every state plus the migrated rows")
        void summary() {
            when(store.countByState(FirewallExemptionState.REQUESTED)).thenReturn(2L);
            when(store.countByState(FirewallExemptionState.APPROVED)).thenReturn(3L);
            when(store.countByKeyKind(FirewallComponentKeyKind.LEGACY_COORDINATE)).thenReturn(4L);

            ExemptionService.ExemptionSummary summary = service.summary();

            assertThat(summary.requested()).isEqualTo(2);
            assertThat(summary.approved()).isEqualTo(3);
            assertThat(summary.legacy()).isEqualTo(4);
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private static ComponentIdentity identity() throws Exception {
        return new ComponentIdentity.Purl(
                new PackageURL("maven", "com.acme", "util", "1.0.0", null, null));
    }

    private static ExemptionRequest request(FirewallExemptionScope scope, Instant suggestedExpiry) {
        return new ExemptionRequest(
                PURL, scope, REPOSITORY, null, List.of(), suggestedExpiry, "needed for the release", "dev");
    }

    private UUID given(FirewallExemptionState state) {
        return given(state, null);
    }

    private UUID given(FirewallExemptionState state, Instant expiresAt) {
        FirewallExemptionEntity entity = entity(state, expiresAt);
        when(store.findById(entity.getId())).thenReturn(Optional.of(entity));
        return entity.getId();
    }

    private static FirewallExemptionEntity entity(FirewallExemptionState state, Instant expiresAt) {
        FirewallExemptionEntity entity = new FirewallExemptionEntity();
        entity.setId(UUID.randomUUID());
        entity.setComponentKey(PURL);
        entity.setKeyKind(FirewallComponentKeyKind.PURL);
        entity.setScopeType(FirewallExemptionScope.VERSION);
        entity.setRepositoryId(REPOSITORY);
        entity.setState(state);
        entity.setExpiresAt(expiresAt);
        entity.setJustification("needed for the release");
        entity.setRequestedBy("dev");
        entity.setRequestedAt(NOW.minus(Duration.ofDays(1)));
        if (state == FirewallExemptionState.APPROVED) {
            entity.setApprovedBy("ops");
            entity.setApprovedAt(NOW.minus(Duration.ofDays(1)));
        }
        return entity;
    }
}
