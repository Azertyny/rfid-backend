package com.rfidback.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Checks every existing route of the access matrix in spec 008 for each profile (SC-001). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccessMatrixSecurityTest {

    enum Profile { ANONYMOUS, OPERATEUR, ADMINISTRATEUR }

    record Route(HttpMethod method, String path, String body, Set<Profile> allowed) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private static final String ID = "00000000-0000-0000-0000-000000000001";
    private static final Set<Profile> ADMIN_ONLY = EnumSet.of(Profile.ADMINISTRATEUR);
    private static final Set<Profile> LOGGED_IN = EnumSet.of(Profile.OPERATEUR, Profile.ADMINISTRATEUR);
    private static final Set<Profile> EVERYONE = EnumSet.allOf(Profile.class);

    private static final String PICKER_BODY = "{\"lastname\":\"Matrix\",\"firstname\":\"Test\"}";

    @Autowired
    private MockMvc mockMvc;

    static Stream<Arguments> matrix() {
        List<Route> routes = List.of(
                new Route(HttpMethod.GET, "/api/pickers", null, LOGGED_IN),
                new Route(HttpMethod.GET, "/api/pickers/" + ID, null, LOGGED_IN),
                new Route(HttpMethod.POST, "/api/pickers", PICKER_BODY, ADMIN_ONLY),
                new Route(HttpMethod.PUT, "/api/pickers/" + ID, PICKER_BODY, ADMIN_ONLY),
                new Route(HttpMethod.DELETE, "/api/pickers/" + ID, null, ADMIN_ONLY),
                new Route(HttpMethod.GET, "/api/readers", null, LOGGED_IN),
                new Route(HttpMethod.POST, "/api/readers",
                        "{\"uid\":\"Matrix reader " + UUID.randomUUID() + "\"}", ADMIN_ONLY),
                new Route(HttpMethod.POST, "/api/tags/buckets/9999", "{\"uids\":[\"MATRIX-TAG\"]}", ADMIN_ONLY),
                new Route(HttpMethod.GET, "/api/buckets", null, ADMIN_ONLY),
                new Route(HttpMethod.GET, "/api/buckets/" + ID, null, ADMIN_ONLY),
                new Route(HttpMethod.PUT, "/api/buckets/" + ID + "/picker", "{\"pickerId\":\"" + ID + "\"}",
                        ADMIN_ONLY),
                new Route(HttpMethod.DELETE, "/api/buckets/" + ID + "/picker", null, ADMIN_ONLY),
                new Route(HttpMethod.GET, "/api/records/readers/unknown-reader", null, LOGGED_IN),
                new Route(HttpMethod.PATCH, "/api/records/" + ID + "/conformity", "{\"isCompliant\":true}",
                        LOGGED_IN),
                new Route(HttpMethod.GET, "/api/users", null, ADMIN_ONLY),
                new Route(HttpMethod.POST, "/api/users",
                        "{\"username\":\"matrix-user\",\"password\":\"matrix-password\",\"role\":\"OPERATEUR\"}",
                        ADMIN_ONLY),
                new Route(HttpMethod.PATCH, "/api/users/" + ID, "{\"enabled\":true}", ADMIN_ONLY),
                new Route(HttpMethod.PUT, "/api/users/" + ID + "/password", "{\"password\":\"another-password\"}",
                        ADMIN_ONLY),
                new Route(HttpMethod.GET, "/api/auth/me", null, LOGGED_IN),
                new Route(HttpMethod.POST, "/api/auth/logout", null, LOGGED_IN),
                new Route(HttpMethod.GET, "/actuator/health", null, EVERYONE));
        return routes.stream()
                .flatMap(route -> Stream.of(Profile.values()).map(profile -> Arguments.of(route, profile)));
    }

    @ParameterizedTest(name = "{1} → {0}")
    @MethodSource("matrix")
    void accessMatchesSpecMatrix(Route route, Profile profile) throws Exception {
        MockHttpServletRequestBuilder builder = request(route.method(), route.path()).with(csrf());
        if (route.body() != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(route.body());
        }
        if (profile != Profile.ANONYMOUS) {
            builder.with(user(profile.name().toLowerCase(Locale.ROOT)).roles(profile.name()));
        }

        int status = mockMvc.perform(builder).andReturn().getResponse().getStatus();

        if (route.allowed().contains(profile)) {
            // Allowed: only the authorization outcome matters, the controller may answer 404/400 for fake ids.
            assertThat(status).isNotIn(401, 403);
        } else if (profile == Profile.ANONYMOUS) {
            assertThat(status).isEqualTo(401);
        } else {
            assertThat(status).isEqualTo(403);
        }
    }

    @Test
    void anonymousWrite_withoutCsrfToken_returns403() throws Exception {
        mockMvc.perform(post("/api/pickers").contentType(MediaType.APPLICATION_JSON).content(PICKER_BODY))
                .andExpect(status().isForbidden());
    }
}
