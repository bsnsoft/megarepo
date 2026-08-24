package de.bsnsoft.megarepo.app.firewall;

import de.bsnsoft.megarepo.repository.firewall.identity.PurlBuilder;
import de.bsnsoft.megarepo.repository.firewall.identity.PurlMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Proves that the per-format {@link PurlMapper} beans are actually discovered
 * and collected — the wiring the design relies on so that
 * {@code megarepo-repository} does not have to depend on all six format
 * modules.
 *
 * <p>This module is where the proof belongs: the format modules are
 * {@code runtimeOnly} dependencies of {@code megarepo-app}, so they are on the
 * test runtime classpath exactly as they are in the shipped application, while
 * {@code megarepo-repository} is compile-visible. No database and no web
 * environment are involved.
 */
class PurlMapperDiscoveryTest {

    private static final Set<String> EXPECTED_MAPPERS = Set.of(
            "de.bsnsoft.megarepo.format.maven.firewall.MavenPurlMapper",
            "de.bsnsoft.megarepo.format.npm.firewall.NpmPurlMapper",
            "de.bsnsoft.megarepo.format.pypi.firewall.PypiPurlMapper",
            "de.bsnsoft.megarepo.format.nuget.firewall.NugetPurlMapper",
            "de.bsnsoft.megarepo.format.raw.firewall.RawPurlMapper",
            "de.bsnsoft.megarepo.format.docker.firewall.DockerPurlMapper");

    @Test
    void everyFormatModuleShipsExactlyOnePurlMapper() {
        assertEquals(new TreeSet<>(EXPECTED_MAPPERS), new TreeSet<>(scanForMapperClassNames()));
    }

    @Test
    void everyPurlMapperIsAnnotatedAsASpringBean() throws Exception {
        for (String className : scanForMapperClassNames()) {
            Class<?> type = Class.forName(className);
            assertNotNull(AnnotationUtils.findAnnotation(type, Component.class),
                    className + " must be a @Component so Spring can collect it");
        }
    }

    @Test
    void purlBuilderCollectsEveryFormatIncludingTheMavenAlias() throws Exception {
        List<Class<?>> mappers = scanForMapperClassNames().stream()
                .map(PurlMapperDiscoveryTest::load)
                .collect(Collectors.toList());

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(mappers.toArray(Class<?>[]::new));
            // PypiPurlMapper's collaborator; registered explicitly so the context
            // stays free of the format modules' storage and database beans.
            context.register(Class.forName("de.bsnsoft.megarepo.format.pypi.naming.PythonNameNormalizer"));
            context.register(PurlBuilder.class);
            context.refresh();

            PurlBuilder builder = context.getBean(PurlBuilder.class);

            assertEquals(EXPECTED_MAPPERS.size(), context.getBeansOfType(PurlMapper.class).size());
            assertEquals(
                    Set.of("maven2", "maven", "npm", "pypi", "nuget", "raw", "docker"),
                    builder.supportedFormatKeys());
        }
    }

    private static Set<String> scanForMapperClassNames() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(PurlMapper.class));
        return scanner.findCandidateComponents("de.bsnsoft.megarepo").stream()
                .map(BeanDefinition::getBeanClassName)
                .collect(Collectors.toSet());
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(className, e);
        }
    }
}
