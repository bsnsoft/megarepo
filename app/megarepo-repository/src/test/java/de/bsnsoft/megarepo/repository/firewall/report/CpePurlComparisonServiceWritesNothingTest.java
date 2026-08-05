package de.bsnsoft.megarepo.repository.firewall.report;

import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.AdvisoryAffectedJpaRepository;
import de.bsnsoft.megarepo.database.repository.AdvisoryJpaRepository;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.database.repository.CveAffectedProductJpaRepository;
import de.bsnsoft.megarepo.database.repository.CveEntryJpaRepository;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryLookupService;
import de.bsnsoft.megarepo.repository.advisory.AdvisoryMergeService;
import de.bsnsoft.megarepo.repository.advisory.IdentityAliasResolver;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlBuilder;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockingDetails;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The report must not change anything it looks at.
 *
 * <p>Asserting that row counts are unchanged (which the database test does)
 * proves the outcome; this proves the mechanism. Every repository is a mock, and
 * after a full run the invocations are inspected: if any method whose name
 * implies a write was ever called, the test fails with that method named. A
 * future change that adds a {@code save} for caching or bookkeeping is caught
 * here even if it happens to be idempotent.
 *
 * <p>Fast and offline — no container, no Spring context.
 */
class CpePurlComparisonServiceWritesNothingTest {

    private static final Set<String> WRITE_PREFIXES =
            Set.of("save", "delete", "remove", "insert", "update", "persist", "merge", "flush");

    @Test
    @DisplayName("a full run touches no mutating repository method")
    void runNeverWrites() {
        ComponentJpaRepository components = mock(ComponentJpaRepository.class);
        CveAffectedProductJpaRepository cveAffected = mock(CveAffectedProductJpaRepository.class);
        CveEntryJpaRepository cveEntries = mock(CveEntryJpaRepository.class);
        AdvisoryAffectedJpaRepository advisoryAffected = mock(AdvisoryAffectedJpaRepository.class);
        AdvisoryJpaRepository advisories = mock(AdvisoryJpaRepository.class);

        when(components.findAllByIdNotNull(any())).thenReturn(List.of(maven("com.acme", "util", "1.0")));
        when(cveAffected.findByProductIn(any())).thenReturn(List.of());
        when(cveEntries.findAllById(any())).thenReturn(List.of());
        when(advisoryAffected.findByPurlCoordinates(any(), any(), any())).thenReturn(List.of());
        when(advisoryAffected.findByPurlTypeAndPurlNameIn(any(), any())).thenReturn(List.of());
        when(advisories.findByIdInAndWithdrawnAtIsNull(any())).thenReturn(List.of());

        CpePurlComparisonService service = service(
                components, cveAffected, cveEntries, advisoryAffected, advisories);

        CpePurlComparisonReport report =
                service.run(ComparisonReportRequest.over("SYNTHETIC unit-test component"));

        assertThat(report.summary().componentsScanned()).isEqualTo(1);
        assertNoWrites(components, cveAffected, cveEntries, advisoryAffected, advisories);
    }

    @Test
    @DisplayName("neither does a run that finds nothing to scan")
    void emptyInstanceNeverWrites() {
        ComponentJpaRepository components = mock(ComponentJpaRepository.class);
        CveAffectedProductJpaRepository cveAffected = mock(CveAffectedProductJpaRepository.class);
        CveEntryJpaRepository cveEntries = mock(CveEntryJpaRepository.class);
        AdvisoryAffectedJpaRepository advisoryAffected = mock(AdvisoryAffectedJpaRepository.class);
        AdvisoryJpaRepository advisories = mock(AdvisoryJpaRepository.class);

        when(components.findAllByIdNotNull(any())).thenReturn(List.of());

        CpePurlComparisonService service = service(
                components, cveAffected, cveEntries, advisoryAffected, advisories);

        CpePurlComparisonReport report = service.run();

        assertThat(report.summary().componentsScanned()).isZero();
        assertThat(report.notes()).anyMatch(note -> note.contains("No components were scanned"));
        assertNoWrites(components, cveAffected, cveEntries, advisoryAffected, advisories);
    }

    private static CpePurlComparisonService service(
            ComponentJpaRepository components,
            CveAffectedProductJpaRepository cveAffected,
            CveEntryJpaRepository cveEntries,
            AdvisoryAffectedJpaRepository advisoryAffected,
            AdvisoryJpaRepository advisories) {

        AdvisoryLookupService lookup = new AdvisoryLookupService(
                advisoryAffected, advisories, new AdvisoryMergeService(new IdentityAliasResolver()));
        return new CpePurlComparisonService(
                components,
                new PurlBuilder(List.of(new MavenishPurlMapper())),
                new LegacyCpeProbe(cveAffected),
                new PurlAdvisoryProbe(lookup, advisoryAffected, advisories),
                cveEntries,
                cveAffected,
                advisories,
                advisoryAffected);
    }

    private static void assertNoWrites(Object... mocks) {
        List<String> writes = new ArrayList<>();
        for (Object target : mocks) {
            MockingDetails details = Mockito.mockingDetails(target);
            for (Invocation invocation : details.getInvocations()) {
                String method = invocation.getMethod().getName().toLowerCase(Locale.ROOT);
                if (WRITE_PREFIXES.stream().anyMatch(method::startsWith)) {
                    writes.add(invocation.getMethod().getDeclaringClass().getSimpleName()
                            + "." + invocation.getMethod().getName());
                }
            }
        }
        assertThat(writes)
                .as("the comparison report must only ever read")
                .isEmpty();
    }

    private static ComponentEntity maven(String namespace, String name, String version) {
        ComponentEntity component = new ComponentEntity();
        component.setId(UUID.randomUUID());
        component.setRepositoryId(UUID.randomUUID());
        component.setFormat("maven2");
        component.setNamespace(namespace);
        component.setName(name);
        component.setVersion(version);
        return component;
    }

    /**
     * A stand-in for {@code MavenPurlMapper}, which lives in a module this one
     * cannot depend on. The real mappers are exercised by the database test in
     * {@code megarepo-app}; here all that matters is that a component resolves
     * to a purl so the purl side of the comparison actually runs.
     */
    private static final class MavenishPurlMapper implements PurlMapper {

        @Override
        public String format() {
            return "maven2";
        }

        @Override
        public java.util.Optional<com.github.packageurl.PackageURL> toPurl(
                ComponentEntity component) {
            try {
                return java.util.Optional.of(new com.github.packageurl.PackageURL(
                        "maven",
                        component.getNamespace(),
                        component.getName(),
                        component.getVersion(),
                        null,
                        null));
            } catch (com.github.packageurl.MalformedPackageURLException e) {
                return java.util.Optional.empty();
            }
        }
    }
}
