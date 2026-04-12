package de.bsnsoft.megarepo.core.format;

import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Optional;

public interface FormatRequestHandler {

    FormatResponse handleHostedGet(RepositoryConfig repo, String path, HttpServletRequest request);

    FormatResponse handleHostedPut(RepositoryConfig repo, String path, HttpServletRequest request);

    FormatResponse handleHostedDelete(RepositoryConfig repo, String path, HttpServletRequest request);

    FormatResponse handleProxyGet(RepositoryConfig repo, String path, HttpServletRequest request);

    FormatResponse handleGroupGet(RepositoryConfig repo, String path, HttpServletRequest request);

    boolean isMetadataPath(String path);

    Optional<FormatResponse> mergeMetadata(RepositoryConfig groupRepo, String path, List<FormatResponse> memberResponses);
}
