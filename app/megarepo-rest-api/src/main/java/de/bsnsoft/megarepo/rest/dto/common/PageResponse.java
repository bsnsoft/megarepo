package de.bsnsoft.megarepo.rest.dto.common;

import java.util.List;

public record PageResponse<T>(List<T> items, String continuationToken) {}
