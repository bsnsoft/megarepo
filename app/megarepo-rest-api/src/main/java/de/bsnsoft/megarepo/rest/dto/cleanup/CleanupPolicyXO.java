package de.bsnsoft.megarepo.rest.dto.cleanup;

import java.util.Map;

public record CleanupPolicyXO(String name, String format, String notes, Map<String, Object> criteria) {}
