package de.bsnsoft.megarepo.format.pypi.simple;

import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.format.pypi.naming.PythonNameNormalizer;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeSet;

/**
 * Generates the PEP 503 Simple Repository API index page.
 * Lists all unique package names in the repository.
 */
@Component
public class SimpleIndexGenerator {

    private final ComponentJpaRepository componentRepository;
    private final PythonNameNormalizer nameNormalizer;

    public SimpleIndexGenerator(
            ComponentJpaRepository componentRepository, PythonNameNormalizer nameNormalizer) {
        this.componentRepository = componentRepository;
        this.nameNormalizer = nameNormalizer;
    }

    public FormatResponse generate(RepositoryConfig repo) {
        // Fetch all components for this repository and collect distinct normalized names
        var components = componentRepository.findByRepositoryId(repo.id(), Pageable.unpaged());

        var packageNames = new TreeSet<String>();
        for (ComponentEntity component : components) {
            String normalized = nameNormalizer.normalize(component.getName());
            packageNames.add(normalized);
        }

        var html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html><body>\n");
        for (String name : packageNames) {
            html.append("  <a href=\"").append(name).append("/\">").append(name).append("</a>\n");
        }
        html.append("</body></html>\n");

        byte[] bytes = html.toString().getBytes(StandardCharsets.UTF_8);
        return new ContentResponse(
                new ByteArrayInputStream(bytes),
                "text/html;charset=utf-8",
                bytes.length,
                Map.of(),
                Map.of());
    }
}
