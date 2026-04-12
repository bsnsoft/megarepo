package de.bsnsoft.megarepo.format.maven;

import de.bsnsoft.megarepo.core.format.FormatSearchContributor;
import org.springframework.stereotype.Component;

/**
 * Maven-specific search contributor. Currently a marker implementation;
 * Maven-specific search fields (groupId, artifactId, classifier, etc.)
 * will be added in a future sprint.
 */
@Component
public class MavenSearchContributor implements FormatSearchContributor {}
