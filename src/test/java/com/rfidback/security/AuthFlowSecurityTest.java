package com.rfidback.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.rfidback.entity.Role;
import com.rfidback.entity.UserEntity;
import com.rfidback.repository.UserRepository;

import jakarta.servlet.http.Cookie;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowSecurityTest {

    private static final String ADMIN_PASSWORD = "admin-password";
    private static final String OPERATOR_PASSWORD = "operator-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        userRepository.save(user("admin", ADMIN_PASSWORD, Role.ADMINISTRATEUR, true));
        userRepository.save(user("disabled-op", OPERATOR_PASSWORD, Role.OPERATEUR, false));
    }

    @Test
    void currentUser_withoutSession_returns401() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void login_withValidCredentials_opensSessionAndReturnsCurrentUser() throws Exception {
        MvcResult result = mockMvc.perform(login("admin", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.role").value("ADMINISTRATEUR"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"));
    }

    @Test
    void login_isCaseAndSpaceInsensitiveOnUsername() throws Exception {
        mockMvc.perform(login("  ADMIN ", ADMIN_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void login_withWrongPasswordOrDisabledAccount_returnsSame401() throws Exception {
        MvcResult wrongPassword = mockMvc.perform(login("admin", "not-the-password"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        MvcResult disabledAccount = mockMvc.perform(login("disabled-op", OPERATOR_PASSWORD))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(disabledAccount.getResponse().getErrorMessage())
                .isEqualTo(wrongPassword.getResponse().getErrorMessage());
        assertThat(disabledAccount.getResponse().getContentAsString())
                .isEqualTo(wrongPassword.getResponse().getContentAsString());
    }

    @Test
    void login_changesSessionId() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String idBeforeLogin = session.getId();

        MvcResult result = mockMvc.perform(login("admin", ADMIN_PASSWORD).session(session))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getRequest().getSession(false).getId()).isNotEqualTo(idBeforeLogin);
    }

    @Test
    void logout_invalidatesSession() throws Exception {
        MockHttpSession session = loginAndGetSession("admin", ADMIN_PASSWORD);

        mockMvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void login_returnsFreshCsrfCookieUsableForTheNextWrite() throws Exception {
        // Like a browser: a CSRF cookie already exists from an earlier anonymous call.
        Cookie anonymousCsrfCookie = mockMvc.perform(get("/api/auth/me")).andReturn()
                .getResponse().getCookie("XSRF-TOKEN");
        assertThat(anonymousCsrfCookie).isNotNull();

        MvcResult result = mockMvc.perform(login("admin", ADMIN_PASSWORD).cookie(anonymousCsrfCookie))
                .andExpect(status().isOk())
                .andReturn();
        Cookie csrfCookie = lastCookie(result, "XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        assertThat(csrfCookie.getValue()).isNotBlank();
        assertThat(csrfCookie.getValue()).isNotEqualTo(anonymousCsrfCookie.getValue());

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mockMvc.perform(post("/api/auth/logout").session(session)
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().isNoContent());
    }

    @Test
    void authenticatedWrite_withoutCsrfToken_returns403() throws Exception {
        MockHttpSession session = loginAndGetSession("admin", ADMIN_PASSWORD);

        mockMvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isForbidden());
    }

    private static Cookie lastCookie(MvcResult result, String name) {
        Cookie last = null;
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (name.equals(cookie.getName())) {
                last = cookie;
            }
        }
        return last;
    }

    private MockHttpSession loginAndGetSession(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(login(username, password)).andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static MockHttpServletRequestBuilder login(String username, String password) {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password));
    }

    private UserEntity user(String username, String password, Role role, boolean enabled) {
        return UserEntity.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .role(role)
                .enabled(enabled)
                .build();
    }
}
