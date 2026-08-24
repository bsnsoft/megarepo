package de.bsnsoft.megarepo.repository.firewall.report;

import java.util.List;
import java.util.UUID;

/**
 * The comparison for one stored component.
 *
 * @param componentId the {@code components.id} row this was computed from, so a
 *     reader can go and look at the component itself
 * @param repositoryId the repository the component lives in
 * @param format MegaRepo's format key, e.g. {@code maven2}
 * @param namespace the coordinate the legacy CPE lookup accepts and never reads
 * @param name artifact name — the only coordinate the legacy lookup does read
 * @param version component version
 * @param identityKey the identity the firewall derived: a canonical purl, or an
 *     {@code unidentified:…} diagnostic key
 * @param identified {@code true} when {@link #identityKey()} is a purl
 * @param verdict the coarse outcome
 * @param deltas every vulnerability either side reported, one entry each
 */
public record ComponentComparison(
        UUID componentId,
        UUID repositoryId,
        String format,
        String namespace,
        String name,
        String version,
        String identityKey,
        boolean identified,
        ComparisonVerdict verdict,
        List<VulnerabilityDelta> deltas) {

    public ComponentComparison {
        deltas = deltas == null ? List.of() : List.copyOf(deltas);
    }

    /** {@code group:name:version}-ish label for the human-readable report. */
    public String coordinates() {
        StringBuilder out = new StringBuilder();
        if (namespace != null && !namespace.isBlank()) {
            out.append(namespace).append(':');
        }
        out.append(name == null ? "?" : name);
        out.append('@').append(version == null ? "?" : version);
        return out.toString();
    }

    /** {@code true} when this component has at least one delta of the given kind. */
    public boolean has(DeltaKind kind) {
        return deltas.stream().anyMatch(delta -> delta.kind() == kind);
    }
}
