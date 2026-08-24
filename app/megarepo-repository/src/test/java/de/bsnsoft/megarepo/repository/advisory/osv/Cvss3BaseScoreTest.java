package de.bsnsoft.megarepo.repository.advisory.osv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Scores checked against the published base scores of the advisories the vectors come
 * from. A wrong score here does not look like a bug — it looks like a policy that blocks
 * the wrong things — so the values are pinned rather than recomputed.
 */
class Cvss3BaseScoreTest {

    @Test
    void scoresLog4Shell() {
        assertEquals(
                10.0,
                Cvss3BaseScore.fromVector("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H"),
                0.001);
    }

    @Test
    void scoresAcrossTheBands() {
        // CVE-2021-45046 (Log4j 2.15 follow-up), published 9.0.
        assertEquals(
                9.0,
                Cvss3BaseScore.fromVector("CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:C/C:H/I:H/A:H"),
                0.001);
        // CVE-2022-42889 (Commons Text), published 9.8.
        assertEquals(
                9.8,
                Cvss3BaseScore.fromVector("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"),
                0.001);
        // A local, low-impact information disclosure: 5.5.
        assertEquals(
                5.5,
                Cvss3BaseScore.fromVector("CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N"),
                0.001);
        // Requires interaction, limited confidentiality only: 4.3.
        assertEquals(
                4.3,
                Cvss3BaseScore.fromVector("CVSS:3.1/AV:N/AC:L/PR:N/UI:R/S:U/C:L/I:N/A:N"),
                0.001);
    }

    @Test
    void scoresCvss30VectorsToo() {
        assertEquals(
                9.8,
                Cvss3BaseScore.fromVector("CVSS:3.0/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"),
                0.001);
    }

    @Test
    void noImpactScoresZero() {
        assertEquals(
                0.0,
                Cvss3BaseScore.fromVector("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:N"),
                0.001);
    }

    @Test
    void returnsNullRatherThanGuessing() {
        assertNull(Cvss3BaseScore.fromVector(null));
        assertNull(Cvss3BaseScore.fromVector("not a vector"));
        // v2 and v4 are stored but not scored.
        assertNull(Cvss3BaseScore.fromVector("AV:N/AC:L/Au:N/C:P/I:P/A:P"));
        assertNull(Cvss3BaseScore.fromVector(
                "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N"));
        // Incomplete base metrics: no score, not a partial one.
        assertNull(Cvss3BaseScore.fromVector("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H"));
        assertNull(Cvss3BaseScore.fromVector("CVSS:3.1/AV:X/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"));
    }

    @Test
    void mapsScoresOntoSeverityBands() {
        assertEquals("NONE", Cvss3BaseScore.severityBand(0.0));
        assertEquals("LOW", Cvss3BaseScore.severityBand(3.9));
        assertEquals("MEDIUM", Cvss3BaseScore.severityBand(4.0));
        assertEquals("HIGH", Cvss3BaseScore.severityBand(7.0));
        assertEquals("CRITICAL", Cvss3BaseScore.severityBand(9.0));
        assertNull(Cvss3BaseScore.severityBand(null));
    }

    @Test
    void roundsUpTheWayTheSpecificationDoes() {
        // 3.7487… must round to 3.8, and a value that lands exactly on a tenth must not
        // be pushed to the next one by a floating-point crumb.
        Double partial = Cvss3BaseScore.fromVector("CVSS:3.1/AV:N/AC:L/PR:H/UI:N/S:U/C:L/I:L/A:N");
        assertNotNull(partial);
        assertEquals(3.8, partial, 0.001);
        assertEquals(
                7.8,
                Cvss3BaseScore.fromVector("CVSS:3.1/AV:L/AC:L/PR:N/UI:R/S:U/C:H/I:H/A:H"),
                0.001);
    }
}
