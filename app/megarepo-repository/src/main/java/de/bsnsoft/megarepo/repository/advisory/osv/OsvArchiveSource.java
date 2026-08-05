package de.bsnsoft.megarepo.repository.advisory.osv;

import de.bsnsoft.megarepo.repository.advisory.AdvisorySyncException;

import java.io.InputStream;

/**
 * Opens the OSV bulk export for one ecosystem.
 *
 * <p>The seam that keeps {@link OsvAdvisorySource} testable without a network: the
 * production implementation streams a ZIP over HTTP, a test hands over a ZIP it built in
 * memory. Nothing above this interface knows which.
 *
 * <p>The returned stream is the caller's to close, and it is a <em>stream</em> on purpose
 * — the npm archive is tens of megabytes and buffering it whole would trade a bounded
 * background sync for an unbounded one.
 */
public interface OsvArchiveSource {

    /**
     * @param ecosystem which archive to open
     * @return the ZIP byte stream, never null
     * @throws AdvisorySyncException when the archive cannot be reached or the response is
     *     not one
     */
    InputStream openArchive(OsvEcosystem ecosystem) throws AdvisorySyncException;
}
