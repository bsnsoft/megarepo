package de.bsnsoft.megarepo.format.pypi.naming;

import org.springframework.stereotype.Component;

/**
 * Normalizes Python package names per PEP 503.
 * Lowercases the name and replaces runs of [-_.] with a single hyphen.
 */
@Component
public class PythonNameNormalizer {

    public String normalize(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return name.toLowerCase().replaceAll("[-_.]+", "-");
    }
}
