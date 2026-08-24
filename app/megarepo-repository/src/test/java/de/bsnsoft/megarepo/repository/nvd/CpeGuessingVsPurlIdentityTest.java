package de.bsnsoft.megarepo.repository.nvd;

import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.database.repository.CveAffectedProductJpaRepository;
import de.bsnsoft.megarepo.database.repository.CveEntryJpaRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the defect that purl identity replaces, and shows the replacement fixing it.
 *
 * <p>The customer's report: <em>"Components are matched to CVEs by guessing CPE
 * product names from the artifact name. This produces both false positives and
 * missed vulnerabilities."</em>
 *
 * <p>The first test in each pair is a characterisation test over the
 * <b>current</b> NVD lookup. It asserts the broken behaviour on purpose, so the
 * claim is demonstrated by the build rather than asserted in a document. Those
 * tests are expected to be deleted together with {@link NvdCveLookupService}
 * once the advisory sources replace it.
 *
 * <p>Nothing here touches the network or the database.
 */
class CpeGuessingVsPurlIdentityTest {

    @Test
    void cpeGuessing_collapsesTwoDifferentPackagesOntoOneProduct() {
        // com.acme:util and org.other:util are unrelated packages by different
        // publishers. Drive the real lookup for both and record what actually
        // reaches the CVE query: `namespace` is a parameter of
        // findApplicableCves and is never read, so both packages are matched
        // against the identical CPE product set.
        List<Collection<String>> queried = new ArrayList<>();
        CveEntryJpaRepository cveRepo = mock(CveEntryJpaRepository.class);
        CveAffectedProductJpaRepository affectedRepo = mock(CveAffectedProductJpaRepository.class);
        when(affectedRepo.findByProductIn(anyCollection())).thenAnswer(invocation -> {
            queried.add(new LinkedHashSet<>(invocation.<Collection<String>>getArgument(0)));
            return List.of();
        });
        NvdCveLookupService service = new NvdCveLookupService(cveRepo, affectedRepo);

        service.findApplicableCves("maven2", "com.acme", "util", "1.0");
        service.findApplicableCves("maven2", "org.other", "util", "1.0");

        assertEquals(2, queried.size());
        assertEquals(queried.get(0), queried.get(1),
                "the groupId never reaches the CVE query, so both packages match the same CPE products");
        assertTrue(queried.get(0).contains("util"));
    }

    @Test
    void purlIdentity_keepsTheSameTwoPackagesApart() throws Exception {
        PackageURL acme = new PackageURL("maven", "com.acme", "util", "1.0", null, null);
        PackageURL other = new PackageURL("maven", "org.other", "util", "1.0", null, null);

        assertEquals("pkg:maven/com.acme/util@1.0", acme.canonicalize());
        assertEquals("pkg:maven/org.other/util@1.0", other.canonicalize());
        assertNotEquals(acme.canonicalize(), other.canonicalize());
        assertFalse(acme.isCoordinatesEquals(other));
    }

    @Test
    void cpeGuessing_foldsSubArtifactsIntoTheirBaseProject() {
        // The "first segment before dash" rule widens the match further:
        // log4j-api inherits every CVE filed against the product "log4j",
        // including those that only ever affected log4j-core.
        Set<String> candidates = NvdCveLookupService.buildProductCandidates("log4j-api");

        assertTrue(candidates.contains("log4j"),
                "log4j-api is also matched against the base product log4j");
    }

    @Test
    void purlIdentity_keepsSubArtifactsDistinct() throws Exception {
        PackageURL api =
                new PackageURL("maven", "org.apache.logging.log4j", "log4j-api", "2.17.1", null, null);
        PackageURL core =
                new PackageURL("maven", "org.apache.logging.log4j", "log4j-core", "2.14.1", null, null);

        assertNotEquals(api.canonicalize(), core.canonicalize());
        assertFalse(api.isCoordinatesEquals(core));
    }
}
