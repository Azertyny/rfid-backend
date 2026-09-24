package com.rfidback.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.rfidback.entity.Role;
import com.rfidback.entity.UserEntity;
import com.rfidback.repository.UserRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public BootstrapAdminRunner(UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.security.bootstrap-admin.username:}") String username,
            @Value("${app.security.bootstrap-admin.password:}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.countByRoleAndEnabledTrue(Role.ADMINISTRATEUR) > 0) {
            return;
        }
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            log.warn("No enabled administrator exists and APP_BOOTSTRAP_ADMIN_USERNAME / "
                    + "APP_BOOTSTRAP_ADMIN_PASSWORD are not set: nobody can log in.");
            return;
        }

        String normalizedUsername = UserEntity.normalizeUsername(username);
        if (userRepository.existsByUsername(normalizedUsername)) {
            log.error("No enabled administrator exists, but bootstrap username '{}' is already taken by another "
                    + "account: no administrator was created.", normalizedUsername);
            return;
        }

        userRepository.save(UserEntity.builder()
                .username(normalizedUsername)
                .passwordHash(passwordEncoder.encode(password))
                .role(Role.ADMINISTRATEUR)
                .enabled(true)
                .build());
        log.info("Bootstrap administrator '{}' created", normalizedUsername);
    }
}
