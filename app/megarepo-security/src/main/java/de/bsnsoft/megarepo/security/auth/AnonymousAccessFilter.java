package de.bsnsoft.megarepo.security.auth;

import de.bsnsoft.megarepo.database.entity.AnonymousAccessSettingsEntity;
import de.bsnsoft.megarepo.database.entity.UserEntity;
import de.bsnsoft.megarepo.database.repository.AnonymousAccessJpaRepository;
import de.bsnsoft.megarepo.database.repository.UserJpaRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
public class AnonymousAccessFilter extends OncePerRequestFilter {

    private final AnonymousAccessJpaRepository anonymousAccessJpaRepository;
    private final UserJpaRepository userJpaRepository;

    public AnonymousAccessFilter(
            AnonymousAccessJpaRepository anonymousAccessJpaRepository, UserJpaRepository userJpaRepository) {
        this.anonymousAccessJpaRepository = anonymousAccessJpaRepository;
        this.userJpaRepository = userJpaRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // Only apply anonymous authentication to repository and Docker V2 endpoints.
        // Management API (/api/v1/**) must always require real credentials.
        String path = request.getRequestURI();
        boolean isRepositoryPath = path.startsWith("/repository/") || path.startsWith("/v2/") || path.startsWith("/v2");
        if (isRepositoryPath && SecurityContextHolder.getContext().getAuthentication() == null) {
            Optional<AnonymousAccessSettingsEntity> settings = anonymousAccessJpaRepository.findById(1);

            if (settings.isPresent() && settings.get().isEnabled()) {
                String anonymousUserId = settings.get().getUserId();
                Optional<UserEntity> anonymousUser = userJpaRepository.findById(anonymousUserId);

                if (anonymousUser.isPresent()) {
                    UserEntity user = anonymousUser.get();
                    var authorities = user.getRoles().stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                            .toList();

                    var authentication =
                            new UsernamePasswordAuthenticationToken(user.getUserId(), null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
