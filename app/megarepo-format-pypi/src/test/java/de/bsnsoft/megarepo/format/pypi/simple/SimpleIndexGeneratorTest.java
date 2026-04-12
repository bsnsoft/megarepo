package de.bsnsoft.megarepo.format.pypi.simple;

import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import de.bsnsoft.megarepo.database.entity.ComponentEntity;
import de.bsnsoft.megarepo.database.repository.ComponentJpaRepository;
import de.bsnsoft.megarepo.format.pypi.naming.PythonNameNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimpleIndexGeneratorTest {

    @Mock
    private ComponentJpaRepository componentRepository;

    private SimpleIndexGenerator generator;

    private static final UUID REPO_ID = UUID.randomUUID();
    private static final RepositoryConfig REPO = new RepositoryConfig(
            REPO_ID, "pypi-hosted", "pypi", RepositoryType.HOSTED, true, "default", Map.of());

    @BeforeEach
    void setUp() {
        generator = new SimpleIndexGenerator(componentRepository, new PythonNameNormalizer());
    }

    @Test
    void generate_emptyRepository() throws IOException {
        when(componentRepository.findByRepositoryId(eq(REPO_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var response = generator.generate(REPO);
        assertInstanceOf(ContentResponse.class, response);

        ContentResponse content = (ContentResponse) response;
        assertEquals("text/html;charset=utf-8", content.contentType());

        String html = new String(content.content().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("<html><body>"));
        assertTrue(html.contains("</body></html>"));
        // No package links
        assertTrue(!html.contains("<a href="));
    }

    @Test
    void generate_multiplePackages() throws IOException {
        var comp1 = createComponent("requests", "2.28.0");
        var comp2 = createComponent("Flask", "2.3.2");
        var comp3 = createComponent("requests", "2.29.0"); // duplicate name, should appear once

        when(componentRepository.findByRepositoryId(eq(REPO_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(comp1, comp2, comp3)));

        var response = generator.generate(REPO);
        assertInstanceOf(ContentResponse.class, response);

        ContentResponse content = (ContentResponse) response;
        String html = new String(content.content().readAllBytes(), StandardCharsets.UTF_8);

        // Should contain normalized names (lowercase, sorted)
        assertTrue(html.contains("<a href=\"flask/\">flask</a>"));
        assertTrue(html.contains("<a href=\"requests/\">requests</a>"));

        // "flask" should appear before "requests" (sorted)
        int flaskIdx = html.indexOf("flask/");
        int requestsIdx = html.indexOf("requests/");
        assertTrue(flaskIdx < requestsIdx, "Package names should be sorted alphabetically");
    }

    @Test
    void generate_normalizesPackageNames() throws IOException {
        var comp1 = createComponent("My_Package.Name", "1.0.0");

        when(componentRepository.findByRepositoryId(eq(REPO_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(comp1)));

        var response = generator.generate(REPO);
        ContentResponse content = (ContentResponse) response;
        String html = new String(content.content().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(html.contains("<a href=\"my-package-name/\">my-package-name</a>"));
    }

    @Test
    void generate_htmlContentType() {
        when(componentRepository.findByRepositoryId(eq(REPO_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var response = generator.generate(REPO);
        ContentResponse content = (ContentResponse) response;
        assertEquals("text/html;charset=utf-8", content.contentType());
        assertTrue(content.contentLength() > 0);
    }

    private ComponentEntity createComponent(String name, String version) {
        var component = new ComponentEntity();
        component.setId(UUID.randomUUID());
        component.setRepositoryId(REPO_ID);
        component.setFormat("pypi");
        component.setName(name);
        component.setVersion(version);
        return component;
    }
}
