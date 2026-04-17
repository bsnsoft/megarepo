package de.bsnsoft.megarepo.rest.dto.security;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

public record NvdFirewallSettingsXO(
        boolean enabled,
        @Size(max = 200) String apiKey,
        @DecimalMin("0.0") @DecimalMax("10.0") double cvssThreshold) {}
