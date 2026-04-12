package de.bsnsoft.megarepo.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.database.entity.LdapServerEntity;
import de.bsnsoft.megarepo.rest.advice.GlobalExceptionHandler;
import de.bsnsoft.megarepo.rest.dto.security.LdapServerXO;
import de.bsnsoft.megarepo.security.ldap.LdapServerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class LdapControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private LdapServerService ldapServerService;

    @BeforeEach
    void setUp() {
        var controller = new LdapController(ldapServerService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listLdapServers_returnsOrderedList() throws Exception {
        var server1 = createTestEntity("corporate-ldap", 1);
        var server2 = createTestEntity("dev-ldap", 2);

        when(ldapServerService.findAll()).thenReturn(List.of(server1, server2));

        mockMvc.perform(get("/api/v1/security/ldap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("corporate-ldap"))
                .andExpect(jsonPath("$[0].hostname").value("ldap.example.com"))
                .andExpect(jsonPath("$[0].authPassword").doesNotExist())
                .andExpect(jsonPath("$[1].name").value("dev-ldap"));
    }

    @Test
    void createLdapServer_returns201() throws Exception {
        LdapServerXO request = createTestXO("new-ldap");
        LdapServerEntity savedEntity = createTestEntity("new-ldap", 1);

        when(ldapServerService.create(any())).thenReturn(savedEntity);

        mockMvc.perform(post("/api/v1/security/ldap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/security/ldap/new-ldap"))
                .andExpect(jsonPath("$.name").value("new-ldap"))
                .andExpect(jsonPath("$.hostname").value("ldap.example.com"))
                .andExpect(jsonPath("$.port").value(389))
                .andExpect(jsonPath("$.authPassword").doesNotExist());

        verify(ldapServerService).create(any());
    }

    @Test
    void getLdapServer_returnsServer() throws Exception {
        var entity = createTestEntity("corporate-ldap", 1);
        when(ldapServerService.findByName("corporate-ldap")).thenReturn(entity);

        mockMvc.perform(get("/api/v1/security/ldap/corporate-ldap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("corporate-ldap"))
                .andExpect(jsonPath("$.protocol").value("ldap"))
                .andExpect(jsonPath("$.searchBase").value("dc=example,dc=com"));
    }

    @Test
    void getLdapServer_notFound_returns404() throws Exception {
        when(ldapServerService.findByName("nonexistent"))
                .thenThrow(new NotFoundException("LDAP server not found: nonexistent"));

        mockMvc.perform(get("/api/v1/security/ldap/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("LDAP server not found: nonexistent"));
    }

    @Test
    void updateLdapServer_returns200() throws Exception {
        LdapServerXO request = createTestXO("corporate-ldap");
        LdapServerEntity updatedEntity = createTestEntity("corporate-ldap", 1);
        updatedEntity.setHostname("new-ldap.example.com");

        when(ldapServerService.update(eq("corporate-ldap"), any())).thenReturn(updatedEntity);

        mockMvc.perform(put("/api/v1/security/ldap/corporate-ldap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostname").value("new-ldap.example.com"));
    }

    @Test
    void deleteLdapServer_returns204() throws Exception {
        doNothing().when(ldapServerService).delete("corporate-ldap");

        mockMvc.perform(delete("/api/v1/security/ldap/corporate-ldap"))
                .andExpect(status().isNoContent());

        verify(ldapServerService).delete("corporate-ldap");
    }

    @Test
    void deleteLdapServer_notFound_returns404() throws Exception {
        doThrow(new NotFoundException("LDAP server not found: nonexistent"))
                .when(ldapServerService)
                .delete("nonexistent");

        mockMvc.perform(delete("/api/v1/security/ldap/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void verifyConnection_returnsResult() throws Exception {
        when(ldapServerService.verifyConnection("corporate-ldap")).thenReturn(true);

        mockMvc.perform(post("/api/v1/security/ldap/corporate-ldap/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.server").value("corporate-ldap"));
    }

    @Test
    void changeOrder_returns204() throws Exception {
        doNothing().when(ldapServerService).changeOrder(List.of("ldap-2", "ldap-1"));

        mockMvc.perform(post("/api/v1/security/ldap/change-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of("ldap-2", "ldap-1"))))
                .andExpect(status().isNoContent());

        verify(ldapServerService).changeOrder(List.of("ldap-2", "ldap-1"));
    }

    private static LdapServerEntity createTestEntity(String name, int sortOrder) {
        LdapServerEntity entity = new LdapServerEntity();
        entity.setName(name);
        entity.setSortOrder(sortOrder);
        entity.setProtocol("ldap");
        entity.setHostname("ldap.example.com");
        entity.setPort(389);
        entity.setSearchBase("dc=example,dc=com");
        entity.setAuthScheme("simple");
        entity.setAuthUsername("cn=admin,dc=example,dc=com");
        entity.setAuthPassword("secret");
        entity.setConnectionTimeout(30);
        entity.setRetryDelay(300);
        entity.setMaxRetries(3);
        entity.setUserBaseDn("ou=users,dc=example,dc=com");
        entity.setUserSubtree(true);
        entity.setUserObjectClass("inetOrgPerson");
        entity.setUserIdAttribute("uid");
        entity.setUserNameAttribute("cn");
        entity.setUserEmailAttribute("mail");
        entity.setLdapGroupsAsRoles(true);
        entity.setGroupType("dynamic");
        entity.setGroupBaseDn("ou=groups,dc=example,dc=com");
        entity.setGroupSubtree(true);
        entity.setGroupObjectClass("groupOfNames");
        entity.setGroupIdAttribute("cn");
        entity.setGroupMemberAttribute("member");
        entity.setGroupMemberFormat("uid=${username},${dn}");
        entity.setUserMemberOfAttribute("memberOf");
        entity.setEnabled(true);
        entity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return entity;
    }

    private static LdapServerXO createTestXO(String name) {
        return new LdapServerXO(
                name,
                0,
                "ldap",
                "ldap.example.com",
                389,
                "dc=example,dc=com",
                "simple",
                "cn=admin,dc=example,dc=com",
                "secret",
                30,
                300,
                3,
                "ou=users,dc=example,dc=com",
                true,
                "inetOrgPerson",
                "uid",
                "cn",
                "mail",
                true,
                "dynamic",
                "ou=groups,dc=example,dc=com",
                true,
                "groupOfNames",
                "cn",
                "member",
                "uid=${username},${dn}",
                "memberOf",
                true);
    }
}
