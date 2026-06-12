package de.bsnsoft.megarepo.app.config;

import java.time.Duration;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Allow encoded slashes (%2F) in URL paths. Required for npm scoped packages
     * which use URLs like {@code @scope%2Fname}. Tomcat decodes %2F to / in the
     * servlet path, while getRequestURI() retains the encoded form. The
     * RepositoryRouter.extractPath() handles both cases.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatEncodedSlashCustomizer() {
        return factory -> factory.addConnectorCustomizers(connector -> {
            connector.setEncodedSolidusHandling("decode");
            // Set connection timeout to 10 seconds to prevent thread exhaustion
            // when TLS clients connect to the plain HTTP port (Fixes #112).
            // Without this, a TLS handshake against an HTTP connector blocks
            // a Tomcat thread indefinitely waiting for valid HTTP input.
            connector.setProperty("connectionTimeout", "10000");
        });
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Vite emits content-hashed filenames under /assets/ — safe to cache
        // aggressively; a new build always references new URLs.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).immutable());
        // index.html (and other non-hashed files) must revalidate on every
        // load, otherwise browsers keep serving a stale UI for up to the
        // cache period after an upgrade (it references outdated asset hashes).
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // SPA: forward all client-side routes to index.html
        // Excludes: /api/**, /repository/**, /actuator/**, static assets
        registry.addViewController("/").setViewName("forward:/index.html");
        registry.addViewController("/login").setViewName("forward:/index.html");
        registry.addViewController("/browse").setViewName("forward:/index.html");
        registry.addViewController("/browse/{path}").setViewName("forward:/index.html");
        registry.addViewController("/browse/{path}/{sub}").setViewName("forward:/index.html");
        registry.addViewController("/browse/{path}/{sub}/{id}").setViewName("forward:/index.html");
        registry.addViewController("/search").setViewName("forward:/index.html");
        registry.addViewController("/upload").setViewName("forward:/index.html");
        registry.addViewController("/account").setViewName("forward:/index.html");
        registry.addViewController("/admin/{page}").setViewName("forward:/index.html");
        registry.addViewController("/admin/{page}/{sub}").setViewName("forward:/index.html");
        registry.addViewController("/admin/{page}/{sub}/{action}").setViewName("forward:/index.html");
    }
}
