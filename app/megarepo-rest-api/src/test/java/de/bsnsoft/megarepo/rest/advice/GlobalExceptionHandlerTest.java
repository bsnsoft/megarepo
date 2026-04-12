package de.bsnsoft.megarepo.rest.advice;

import de.bsnsoft.megarepo.core.exception.AccessDeniedException;
import de.bsnsoft.megarepo.core.exception.ConflictException;
import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.core.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ExceptionThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void notFoundExceptionReturns404() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Resource not found"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void validationExceptionReturns400() throws Exception {
        mockMvc.perform(get("/test/validation"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Invalid input data"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void accessDeniedExceptionReturns403() throws Exception {
        mockMvc.perform(get("/test/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Access denied"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void illegalArgumentExceptionReturns400() throws Exception {
        mockMvc.perform(get("/test/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Bad argument"));
    }

    @Test
    void illegalStateExceptionReturns409() throws Exception {
        mockMvc.perform(get("/test/illegal-state"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Bad state"));
    }

    @Test
    void noSuchElementExceptionReturns404() throws Exception {
        mockMvc.perform(get("/test/no-such-element"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void conflictExceptionReturns409() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Already exists"));
    }

    @Test
    void dataIntegrityViolationExceptionReturns409() throws Exception {
        mockMvc.perform(get("/test/data-integrity"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Resource already exists or violates a constraint"));
    }

    @Test
    void genericExceptionReturns500WithoutInternalDetails() throws Exception {
        mockMvc.perform(get("/test/generic"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @RestController
    @RequestMapping("/test")
    static class ExceptionThrowingController {

        @GetMapping("/not-found")
        public void throwNotFound() {
            throw new NotFoundException("Resource not found");
        }

        @GetMapping("/validation")
        public void throwValidation() {
            throw new ValidationException("Invalid input data");
        }

        @GetMapping("/access-denied")
        public void throwAccessDenied() {
            throw new AccessDeniedException("Access denied");
        }

        @GetMapping("/illegal-argument")
        public void throwIllegalArgument() {
            throw new IllegalArgumentException("Bad argument");
        }

        @GetMapping("/illegal-state")
        public void throwIllegalState() {
            throw new IllegalStateException("Bad state");
        }

        @GetMapping("/no-such-element")
        public void throwNoSuchElement() {
            throw new NoSuchElementException("Not found");
        }

        @GetMapping("/conflict")
        public void throwConflict() {
            throw new ConflictException("Already exists");
        }

        @GetMapping("/data-integrity")
        public void throwDataIntegrity() {
            throw new DataIntegrityViolationException("unique constraint violated");
        }

        @GetMapping("/generic")
        public void throwGeneric() {
            throw new RuntimeException("SQL error: connection pool exhausted for db_prod_05");
        }
    }
}
