package de.bsnsoft.megarepo.app.license;

public record LicenseInfo(
        String company,
        String email,
        String issuedAt,
        String validUntil,
        String licenseId,
        boolean valid) {}
