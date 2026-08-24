package de.bsnsoft.megarepo.format.pypi.firewall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.packageurl.PackageURL;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsHttpClient;
import de.bsnsoft.megarepo.repository.firewall.facts.ComponentFactsSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The PyPI facts source against recorded JSON documents, offline.
 *
 * <p>PyPI is the awkward one: three places declare a license and only the newest
 * of them is an identifier. The tests below pin the precedence and, in
 * particular, that a package which pastes its whole license text into
 * {@code info.license} does not end up with that text as its "declared license".
 */
class PypiComponentFactsSourceTest {

    private static final String PYPI = "https://pypi.org/";

    private StubHttp http;
    private PypiComponentFactsSource source;

    @BeforeEach
    void setUp() {
        http = new StubHttp();
        source = new PypiComponentFactsSource(http, new ObjectMapper(), PYPI);
    }

    @Test
    @DisplayName("the SPDX expression wins over the free-text field and the classifiers")
    void licenseExpressionWins() throws Exception {
        http.respond(PYPI + "pypi/requests/2.31.0/json", 200, """
                {
                  "info": {
                    "license": "Apache 2.0",
                    "license_expression": "Apache-2.0",
                    "classifiers": ["License :: OSI Approved :: Apache Software License"]
                  },
                  "urls": [ { "upload_time_iso_8601": "2023-05-22T15:12:00.000000Z" } ]
                }
                """);

        ComponentFactsSource.ResolvedFacts facts =
                source.resolve(new PackageURL("pkg:pypi/requests@2.31.0")).orElseThrow();

        assertThat(facts.declaredLicenses()).containsExactly("Apache-2.0");
        assertThat(facts.publishedAt()).isEqualTo(Instant.parse("2023-05-22T15:12:00Z"));
        assertThat(facts.source()).isEqualTo("pypi-json");
    }

    @Test
    @DisplayName("a whole license text in info.license is not a declared identifier")
    void aPastedLicenseTextIsIgnored() throws Exception {
        String longText = "Permission is hereby granted, free of charge, to any person obtaining "
                + "a copy of this software and associated documentation files";
        http.respond(PYPI + "pypi/verbose/1.0/json", """
                {
                  "info": {
                    "license": "%s",
                    "classifiers": ["License :: OSI Approved :: MIT License"]
                  },
                  "urls": [ { "upload_time_iso_8601": "2020-01-01T00:00:00.000000Z" } ]
                }
                """.formatted(longText));

        ComponentFactsSource.ResolvedFacts facts =
                source.resolve(new PackageURL("pkg:pypi/verbose@1.0")).orElseThrow();

        assertThat(facts.declaredLicenses())
                .as("a 12 kB 'identifier' is noise in an allow-list comparison")
                .containsExactly("MIT License");
    }

    @Test
    @DisplayName("the classifier is kept verbatim rather than translated to an SPDX id")
    void classifiersAreNotTranslated() throws Exception {
        http.respond(PYPI + "pypi/classified/1.0/json", """
                {
                  "info": {
                    "classifiers": [
                      "License :: OSI Approved",
                      "License :: OSI Approved :: BSD License",
                      "Programming Language :: Python :: 3"
                    ]
                  },
                  "urls": [ { "upload_time_iso_8601": "2020-01-01T00:00:00.000000Z" } ]
                }
                """);

        assertThat(source.resolve(new PackageURL("pkg:pypi/classified@1.0"))
                        .orElseThrow().declaredLicenses())
                .as("the category header is not a license, the leaf is")
                .containsExactly("BSD License");
    }

    @Test
    @DisplayName("the earliest upload dates the release, so a late wheel cannot reset MIN_AGE")
    void theEarliestUploadWins() throws Exception {
        http.respond(PYPI + "pypi/multi/1.0/json", """
                {
                  "info": { "license_expression": "MIT" },
                  "urls": [
                    { "upload_time_iso_8601": "2021-09-09T09:09:09.000000Z" },
                    { "upload_time_iso_8601": "2021-01-01T00:00:00.000000Z" },
                    { "upload_time_iso_8601": "2024-01-01T00:00:00.000000Z" }
                  ]
                }
                """);

        assertThat(source.resolve(new PackageURL("pkg:pypi/multi@1.0"))
                        .orElseThrow().publishedAt())
                .isEqualTo(Instant.parse("2021-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("a package that declares nothing resolves with an empty list")
    void nothingDeclaredStillResolves() throws Exception {
        http.respond(PYPI + "pypi/bare/1.0/json", """
                { "info": { "classifiers": [] }, "urls": [] }
                """);

        ComponentFactsSource.ResolvedFacts facts =
                source.resolve(new PackageURL("pkg:pypi/bare@1.0")).orElseThrow();

        assertThat(facts.declaredLicenses()).isEmpty();
        assertThat(facts.publishedAt()).isNull();
    }

    @Test
    @DisplayName("a 404 settles the row")
    void notFoundIsEmpty() throws Exception {
        http.respond(PYPI + "pypi/gone/1.0/json", 404, "");

        assertThat(source.resolve(new PackageURL("pkg:pypi/gone@1.0"))).isEmpty();
    }

    @Test
    @DisplayName("a 5xx is retryable")
    void serverErrorsAreRetryable() {
        http.respond(PYPI + "pypi/busy/1.0/json", 500, "");

        assertThatThrownBy(() -> source.resolve(new PackageURL("pkg:pypi/busy@1.0")))
                .isInstanceOf(ComponentFactsSource.ComponentFactsException.class)
                .hasMessageContaining("500");
    }

    private static final class StubHttp implements ComponentFactsHttpClient {

        private final Map<String, Response> responses = new HashMap<>();

        void respond(String url, String body) {
            respond(url, 200, body);
        }

        void respond(String url, int status, String body) {
            responses.put(url, new Response(status, body, Optional.empty()));
        }

        @Override
        public Response get(String url, Map<String, String> headers) {
            Response response = responses.get(url);
            if (response == null) {
                throw new AssertionError("Unstubbed upstream request: " + url);
            }
            return response;
        }
    }
}
