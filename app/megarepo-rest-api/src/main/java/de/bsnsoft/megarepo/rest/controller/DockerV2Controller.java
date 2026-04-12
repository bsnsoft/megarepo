package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.repository.RepositoryRouter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

import static org.springframework.web.bind.annotation.RequestMethod.DELETE;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.HEAD;
import static org.springframework.web.bind.annotation.RequestMethod.PATCH;
import static org.springframework.web.bind.annotation.RequestMethod.POST;
import static org.springframework.web.bind.annotation.RequestMethod.PUT;

/**
 * Root-level Docker V2 API controller.
 *
 * <p>The Docker CLI sends all registry requests to {@code /v2/} at the server root,
 * but MegaRepo namespaces repositories under {@code /repository/{name}/v2/}.
 * This controller bridges the gap by accepting requests at {@code /v2/**} and
 * delegating them to the {@link RepositoryRouter} as if they arrived under
 * the configured default Docker repository.
 *
 * <p>The default repository name is configurable via
 * {@code megarepo.docker.default-repository} (defaults to {@code docker-hosted}).
 */
@RestController
public class DockerV2Controller {

    private static final Logger log = LoggerFactory.getLogger(DockerV2Controller.class);

    private final RepositoryRouter repositoryRouter;
    private final String defaultRepository;

    public DockerV2Controller(
            RepositoryRouter repositoryRouter,
            @Value("${megarepo.docker.default-repository:docker-hosted}") String defaultRepository) {
        this.repositoryRouter = repositoryRouter;
        this.defaultRepository = defaultRepository;
        log.info("Docker V2 root routing enabled — /v2/** → repository '{}'", defaultRepository);
    }

    @RequestMapping(value = {"/v2", "/v2/**"}, method = {GET, HEAD})
    public void handleGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        repositoryRouter.handleGet(defaultRepository, rewriteRequest(request), response);
    }

    @RequestMapping(value = {"/v2", "/v2/**"}, method = PUT)
    public void handlePut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        repositoryRouter.handlePut(defaultRepository, rewriteRequest(request), response);
    }

    @RequestMapping(value = {"/v2", "/v2/**"}, method = POST)
    public void handlePost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        repositoryRouter.handlePost(defaultRepository, rewriteRequest(request), response);
    }

    @RequestMapping(value = {"/v2", "/v2/**"}, method = PATCH)
    public void handlePatch(HttpServletRequest request, HttpServletResponse response) throws IOException {
        repositoryRouter.handlePatch(defaultRepository, rewriteRequest(request), response);
    }

    @RequestMapping(value = {"/v2", "/v2/**"}, method = DELETE)
    public void handleDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        repositoryRouter.handleDelete(defaultRepository, rewriteRequest(request), response);
    }

    /**
     * Wraps the incoming request so that {@code getRequestURI()} returns
     * a URI under {@code /repository/{defaultRepo}/}, which is what
     * {@link RepositoryRouter#extractPath} expects for path extraction.
     *
     * <p>For example, {@code /v2/myimage/manifests/latest} becomes
     * {@code /repository/docker-hosted/v2/myimage/manifests/latest}.
     */
    private HttpServletRequest rewriteRequest(HttpServletRequest original) {
        String originalUri = original.getRequestURI();
        // Replace leading /v2 with /repository/{defaultRepo}/v2
        String rewrittenUri = "/repository/" + defaultRepository + originalUri;

        return new HttpServletRequestWrapper(original) {
            @Override
            public String getRequestURI() {
                return rewrittenUri;
            }

            @Override
            public StringBuffer getRequestURL() {
                // Reconstruct URL with the rewritten URI
                StringBuffer url = new StringBuffer();
                url.append(original.getScheme());
                url.append("://");
                url.append(original.getServerName());
                if (original.getServerPort() != 80 && original.getServerPort() != 443) {
                    url.append(':').append(original.getServerPort());
                }
                url.append(rewrittenUri);
                return url;
            }
        };
    }
}
