package de.bsnsoft.megarepo.security;

import de.bsnsoft.megarepo.security.auth.AnonymousAccessFilter;
import de.bsnsoft.megarepo.security.auth.JwtAuthenticationFilter;
import de.bsnsoft.megarepo.security.auth.ratelimit.LoginRateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.firewall.StrictHttpFirewall;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtFilter,
            AnonymousAccessFilter anonFilter,
            LoginRateLimitFilter rateLimitFilter,
            AuthenticationProvider ldapAwareAuthenticationProvider)
            throws Exception {
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/repository/**", "/api/**", "/v2/**"))
                .headers(headers -> {
                    headers.contentTypeOptions(Customizer.withDefaults()); // X-Content-Type-Options: nosniff
                    headers.frameOptions(frame -> frame.deny()); // X-Frame-Options: DENY
                    headers.httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true)
                            .maxAgeInSeconds(31536000)); // Strict-Transport-Security: 1 year
                    headers.permissionsPolicy(permissions -> permissions
                            .policy("camera=(), microphone=(), geolocation=()"));
                    headers.contentSecurityPolicy(csp -> csp
                            .policyDirectives(
                                    "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                                            + "img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'"));
                    headers.referrerPolicy(referrer -> referrer
                            .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                    .ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
                })
                .authenticationProvider(ldapAwareAuthenticationProvider)
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/", "/index.html", "/ui/**", "/assets/**", "/favicon.ico", "/static/**")
                        .permitAll()
                        .requestMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/api/v1/api-docs",
                                "/api/v1/api-docs/**",
                                "/api/v1/api-docs.yaml")
                        .permitAll()
                        .requestMatchers("/api/v1/security/auth/**")
                        .permitAll()
                        .requestMatchers("/api/v1/status")
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info")
                        .permitAll()
                        .requestMatchers("/actuator/**")
                        .authenticated()
                        .requestMatchers(HttpMethod.GET, "/repository/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/repository/**")
                        .permitAll()
                        .requestMatchers("/repository/**")
                        .authenticated()
                        .requestMatchers(HttpMethod.GET, "/v2/")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/v2/token")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/v2/token")
                        .permitAll()
                        .requestMatchers("/v2/**")
                        .authenticated()
                        .requestMatchers("/api/v1/**")
                        .authenticated()
                        .anyRequest()
                        .permitAll())
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(anonFilter, UsernamePasswordAuthenticationFilter.class)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Allow encoded slashes (%2F) in URL paths. Required for npm scoped packages
     * which use URLs like {@code @scope%2Fname}. Tomcat's encodedSolidusHandling=decode
     * decodes %2F before it reaches the servlet, so the StrictHttpFirewall only sees
     * the decoded path. We still need to explicitly allow it for edge cases where the
     * raw URI is checked.
     */
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        var firewall = new StrictHttpFirewall();
        firewall.setAllowUrlEncodedSlash(true);
        return web -> web.httpFirewall(firewall);
    }
}
