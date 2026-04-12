package de.bsnsoft.megarepo.app.license;

public record LicenseStatusXO(
        boolean licensed,
        String company,
        String email,
        String issuedAt,
        int activeUsers,
        boolean requiresPurchase,
        String message) {}
