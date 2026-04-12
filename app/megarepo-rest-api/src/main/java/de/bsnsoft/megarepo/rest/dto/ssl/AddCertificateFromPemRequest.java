package de.bsnsoft.megarepo.rest.dto.ssl;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddCertificateFromPemRequest(
        @NotBlank(message = "PEM certificate data is required")
        @Size(max = 65536, message = "PEM data must not exceed 64KB")
        String pem) {}
