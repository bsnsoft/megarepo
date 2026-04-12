package de.bsnsoft.megarepo.rest.dto.routing;

import java.time.Instant;
import java.util.List;

public record RoutingRuleXO(String name, String description, String mode, List<String> matchers, Instant createdAt) {}
