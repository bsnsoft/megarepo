package de.bsnsoft.megarepo.rest.dto.routing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateRoutingRuleRequest(
        @NotBlank String name,
        String description,
        @NotBlank String mode,
        @NotNull List<String> matchers) {}
