package com.rfidback.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.rfidback.entity.Role;
import com.rfidback.entity.UserEntity;
import com.rfidback.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserManagementSecurityTest {

    private static final String ADMIN_PASSWORD = "admin-password";
    private static final String OPERATOR_PASSWORD = "operator-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockHttpSession adminSession;
    private UUID adminId;
    private UUID operatorId;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        adminId = saveUser("admin", ADMIN_PASSWORD, Role.ADMINISTRATEUR).getId();
        operatorId = saveUser("op1", OPERATOR_PASSWORD, Role.OPERATEUR).getId();
        adminSession = login("admin", ADMIN_PASSWORD);
    }

    @Test
    void createUser_returns201WithoutPassword() throws Exception {
        createUser("newop", "new-operator-password", "OPERATEUR")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("newop"))
                .andExpect(jsonPath("$.role").value("OPERATEUR"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.creationDate").isNotEmpty())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void createUser_withExistingUsernameInAnotherCase_returns409() throws Exception {
        createUser("OP1", "another-password", "OPERATEUR").andExpect(status().isConflict());
    }

    @Test
    void createUser_withInvalidUsernameOrShortPassword_returns400() throws Exception {
        createUser("a", "long-enough-password", "OPERATEUR").andExpect(status().isBadRequest());
        createUser("valid-name", "short12", "OPERATEUR").andExpect(status().isBadRequest());
    }

    @Test
    void listUsers_isSortedByUsername() throws Exception {
        mockMvc.perform(get("/api/users").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[0].username").value("admin"))
                .andExpect(jsonPath("$.users[1].username").value("op1"));
    }

    @Test
    void updateUser_withEmptyBody_returns400() throws Exception {
        updateUser(operatorId, "{}").andExpect(status().isBadRequest());
    }

    @Test
    void updateUser_withUnknownId_returns404() throws Exception {
        updateUser(UUID.randomUUID(), "{\"enabled\":false}").andExpect(status().isNotFound());
    }

    @Test
    void disablingUser_closesTheirSessionsAndBlocksLogin() throws Exception {
        MockHttpSession operatorSession = login("op1", OPERATOR_PASSWORD);

        updateUser(operatorId, "{\"enabled\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(get("/api/auth/me").session(operatorSession)).andExpect(status().isUnauthorized());
        mockMvc.perform(loginRequest("op1", OPERATOR_PASSWORD)).andExpect(status().isUnauthorized());
    }

    @Test
    void changingRole_closesTheirSessions() throws Exception {
        MockHttpSession operatorSession = login("op1", OPERATOR_PASSWORD);

        updateUser(operatorId, "{\"role\":\"ADMINISTRATEUR\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMINISTRATEUR"));

        mockMvc.perform(get("/api/auth/me").session(operatorSession)).andExpect(status().isUnauthorized());
    }

    @Test
    void changingOwnRole_closesOwnSession() throws Exception {
        updateUser(operatorId, "{\"role\":\"ADMINISTRATEUR\"}").andExpect(status().isOk());

        updateUser(adminId, "{\"role\":\"OPERATEUR\"}").andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/me").session(adminSession)).andExpect(status().isUnauthorized());
    }

    @Test
    void resetPassword_replacesPasswordAndClosesSessions() throws Exception {
        MockHttpSession operatorSession = login("op1", OPERATOR_PASSWORD);

        mockMvc.perform(put("/api/users/{id}/password", operatorId).session(adminSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"brand-new-password\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").session(operatorSession)).andExpect(status().isUnauthorized());
        mockMvc.perform(loginRequest("op1", OPERATOR_PASSWORD)).andExpect(status().isUnauthorized());
        mockMvc.perform(loginRequest("op1", "brand-new-password")).andExpect(status().isOk());
    }

    @Test
    void lastEnabledAdministrator_cannotBeDisabledOrDemoted() throws Exception {
        updateUser(adminId, "{\"enabled\":false}").andExpect(status().isConflict());
        updateUser(adminId, "{\"role\":\"OPERATEUR\"}").andExpect(status().isConflict());
    }

    private ResultActions createUser(String username, String password, String role) throws Exception {
        return mockMvc.perform(post("/api/users").session(adminSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\",\"role\":\"%s\"}"
                        .formatted(username, password, role)));
    }

    private ResultActions updateUser(UUID userId, String body) throws Exception {
        return mockMvc.perform(patch("/api/users/{id}", userId).session(adminSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private MockHttpSession login(String username, String password) throws Exception {
        return (MockHttpSession) mockMvc.perform(loginRequest(username, password))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    private static MockHttpServletRequestBuilder loginRequest(String username, String password) {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password));
    }

    private UserEntity saveUser(String username, String password, Role role) {
        return userRepository.save(UserEntity.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .role(role)
                .enabled(true)
                .build());
    }
}
