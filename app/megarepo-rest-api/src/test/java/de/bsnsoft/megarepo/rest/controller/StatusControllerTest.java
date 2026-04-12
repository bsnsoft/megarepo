package de.bsnsoft.megarepo.rest.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StatusControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new StatusController("1.2.3-test")).build();
    }

    @Test
    void statusReturns200() throws Exception {
        mockMvc.perform(get("/api/v1/status"))
                .andExpect(status().isOk());
    }

    @Test
    void statusCheckReturnsVersionFromProperty() throws Exception {
        mockMvc.perform(get("/api/v1/status/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.version").value("1.2.3-test"))
                .andExpect(jsonPath("$.edition").value("MegaRepo"));
    }
}
