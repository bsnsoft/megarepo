package de.bsnsoft.megarepo.rest;

import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Standalone MockMvc with the JSON stack the application actually runs with.
 *
 * <p>The application writes and reads JSON with Jackson 2 (see
 * {@code spring.http.converters.preferred-json-mapper} in application.yml). Since
 * Spring Framework 7 the default converters of a plain
 * {@link MockMvcBuilders#standaloneSetup} pick Jackson 3 whenever it is on the
 * classpath — and Flyway puts it there. Jackson 3 rejects a missing primitive
 * ({@code FAIL_ON_NULL_FOR_PRIMITIVES}) where Jackson 2 defaults it, so a test on
 * those defaults would check a JSON behaviour production does not have.
 */
@SuppressWarnings({"deprecation", "removal"})
public final class Jackson2MockMvc {

    private Jackson2MockMvc() {
    }

    public static StandaloneMockMvcBuilder standaloneSetup(Object... controllers) {
        HttpMessageConverters converters = HttpMessageConverters.forServer()
                .registerDefaults()
                .withJsonConverter(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
        List<HttpMessageConverter<?>> list = new ArrayList<>();
        converters.forEach(list::add);
        return MockMvcBuilders.standaloneSetup(controllers)
                .setMessageConverters(list.toArray(HttpMessageConverter<?>[]::new));
    }
}
