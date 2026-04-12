package de.bsnsoft.megarepo.repository;

public record RepositoryMetric(
        String name, String format, String type, long componentCount, long assetCount) {}
