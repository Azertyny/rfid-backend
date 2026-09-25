package com.rfidback.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.rfidback.entity.Role;
import com.rfidback.entity.UserEntity;
import com.rfidback.repository.UserRepository;

class BootstrapAdminRunnerTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        passwordEncoder = Mockito.mock(PasswordEncoder.class);
        when(passwordEncoder.encode("bootstrap-password")).thenReturn("{hash}");
    }

    @Test
    void createsAdministrator_whenNoneExistsAndValuesAreSet() {
        when(userRepository.countByRoleAndEnabledTrue(Role.ADMINISTRATEUR)).thenReturn(0L);

        new BootstrapAdminRunner(userRepository, passwordEncoder, "  Admin ", "bootstrap-password").run(null);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        UserEntity created = captor.getValue();
        assertEquals("admin", created.getUsername());
        assertEquals("{hash}", created.getPasswordHash());
        assertEquals(Role.ADMINISTRATEUR, created.getRole());
        assertTrue(created.isEnabled());
    }

    @Test
    void doesNothing_whenAnEnabledAdministratorExists() {
        when(userRepository.countByRoleAndEnabledTrue(Role.ADMINISTRATEUR)).thenReturn(1L);

        new BootstrapAdminRunner(userRepository, passwordEncoder, "admin", "bootstrap-password").run(null);

        verify(userRepository, never()).save(any());
    }

    @Test
    void doesNothing_whenAValueIsBlank() {
        when(userRepository.countByRoleAndEnabledTrue(Role.ADMINISTRATEUR)).thenReturn(0L);

        new BootstrapAdminRunner(userRepository, passwordEncoder, "admin", " ").run(null);
        new BootstrapAdminRunner(userRepository, passwordEncoder, "", "bootstrap-password").run(null);

        verify(userRepository, never()).save(any());
    }

    @Test
    void doesNothing_whenBootstrapUsernameIsAlreadyTaken() {
        when(userRepository.countByRoleAndEnabledTrue(Role.ADMINISTRATEUR)).thenReturn(0L);
        when(userRepository.existsByUsername("admin")).thenReturn(true);

        new BootstrapAdminRunner(userRepository, passwordEncoder, "admin", "bootstrap-password").run(null);

        verify(userRepository, never()).save(any());
    }
}
