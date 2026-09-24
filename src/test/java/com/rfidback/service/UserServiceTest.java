package com.rfidback.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.rfidback.entity.Role;
import com.rfidback.entity.UserEntity;
import com.rfidback.exception.LastAdministratorException;
import com.rfidback.generated.model.CreateUser;
import com.rfidback.generated.model.ResetPassword;
import com.rfidback.generated.model.UpdateUser;
import com.rfidback.repository.UserRepository;

class UserServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private SessionRegistry sessionRegistry;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        passwordEncoder = Mockito.mock(PasswordEncoder.class);
        sessionRegistry = Mockito.mock(SessionRegistry.class);
        userService = new UserService(userRepository, passwordEncoder, sessionRegistry);
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(passwordEncoder.encode(any())).thenReturn("{hash}");
    }

    @Test
    void createUser_normalizesUsername() {
        userService.createUser(new CreateUser("  OP1 ", "operator-password",
                com.rfidback.generated.model.Role.OPERATEUR));

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertEquals("op1", captor.getValue().getUsername());
        assertEquals("{hash}", captor.getValue().getPasswordHash());
    }

    @Test
    void disablingLastEnabledAdministrator_isRefused() {
        UserEntity admin = existingUser("admin", Role.ADMINISTRATEUR, true);
        when(userRepository.countByRoleAndEnabledTrue(Role.ADMINISTRATEUR)).thenReturn(1L);

        assertThrows(LastAdministratorException.class,
                () -> userService.updateUser(admin.getId(), new UpdateUser().enabled(false)));
        verify(userRepository, never()).save(any());
    }

    @Test
    void demotingLastEnabledAdministrator_isRefused() {
        UserEntity admin = existingUser("admin", Role.ADMINISTRATEUR, true);
        when(userRepository.countByRoleAndEnabledTrue(Role.ADMINISTRATEUR)).thenReturn(1L);

        assertThrows(LastAdministratorException.class, () -> userService.updateUser(admin.getId(),
                new UpdateUser().role(com.rfidback.generated.model.Role.OPERATEUR)));
    }

    @Test
    void disablingAnAdministrator_isAllowedWhenAnotherOneRemains() {
        UserEntity admin = existingUser("admin", Role.ADMINISTRATEUR, true);
        when(userRepository.countByRoleAndEnabledTrue(Role.ADMINISTRATEUR)).thenReturn(2L);

        userService.updateUser(admin.getId(), new UpdateUser().enabled(false));

        verify(userRepository).save(admin);
    }

    @Test
    void disablingUser_expiresTheirSessions() {
        UserEntity operator = existingUser("op1", Role.OPERATEUR, true);
        SessionInformation session = sessionOf("op1");

        userService.updateUser(operator.getId(), new UpdateUser().enabled(false));

        verify(session).expireNow();
    }

    @Test
    void changingRole_expiresTheirSessions() {
        UserEntity operator = existingUser("op1", Role.OPERATEUR, true);
        SessionInformation session = sessionOf("op1");

        userService.updateUser(operator.getId(),
                new UpdateUser().role(com.rfidback.generated.model.Role.ADMINISTRATEUR));

        verify(session).expireNow();
    }

    @Test
    void resetPassword_expiresTheirSessions() {
        UserEntity operator = existingUser("op1", Role.OPERATEUR, true);
        SessionInformation session = sessionOf("op1");

        userService.resetPassword(operator.getId(), new ResetPassword("brand-new-password"));

        verify(session).expireNow();
        assertEquals("{hash}", operator.getPasswordHash());
    }

    @Test
    void otherUsersSessions_areNotExpired() {
        UserEntity operator = existingUser("op1", Role.OPERATEUR, true);
        User otherPrincipal = principal("someone-else");
        SessionInformation otherSession = Mockito.mock(SessionInformation.class);
        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(otherPrincipal));
        when(sessionRegistry.getAllSessions(otherPrincipal, false)).thenReturn(List.of(otherSession));

        userService.updateUser(operator.getId(), new UpdateUser().enabled(false));

        verify(otherSession, never()).expireNow();
    }

    private UserEntity existingUser(String username, Role role, boolean enabled) {
        UserEntity user = UserEntity.builder()
                .id(UUID.randomUUID())
                .username(username)
                .passwordHash("{old}")
                .role(role)
                .enabled(enabled)
                .build();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        return user;
    }

    private SessionInformation sessionOf(String username) {
        User userPrincipal = principal(username);
        SessionInformation session = Mockito.mock(SessionInformation.class);
        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(userPrincipal));
        when(sessionRegistry.getAllSessions(userPrincipal, false)).thenReturn(List.of(session));
        return session;
    }

    private static User principal(String username) {
        return new User(username, "{hash}", List.of());
    }
}
