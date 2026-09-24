package com.rfidback.service;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.entity.Role;
import com.rfidback.entity.UserEntity;
import com.rfidback.exception.LastAdministratorException;
import com.rfidback.exception.UserAlreadyExistsException;
import com.rfidback.exception.UserNotFoundException;
import com.rfidback.generated.model.CreateUser;
import com.rfidback.generated.model.ResetPassword;
import com.rfidback.generated.model.UpdateUser;
import com.rfidback.generated.model.User;
import com.rfidback.generated.model.UsersList;
import com.rfidback.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionRegistry sessionRegistry;

    public UsersList listUsers() {
        UsersList response = new UsersList();
        response.setUsers(userRepository.findAllByOrderByUsernameAsc().stream().map(UserService::toModel).toList());
        return response;
    }

    @Transactional
    public User createUser(CreateUser request) {
        String username = UserEntity.normalizeUsername(request.getUsername());
        if (userRepository.existsByUsername(username)) {
            throw new UserAlreadyExistsException("User %s already exists".formatted(username));
        }
        // Flush so that @CreationTimestamp is set before the entity is mapped into the response.
        UserEntity user = userRepository.saveAndFlush(UserEntity.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(Role.valueOf(request.getRole().name()))
                .enabled(true)
                .build());
        return toModel(user);
    }

    @Transactional
    public User updateUser(UUID userId, UpdateUser request) {
        if (request.getRole() == null && request.getEnabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide a role and/or an enabled state");
        }
        UserEntity user = loadUser(userId);
        Role newRole = request.getRole() == null ? user.getRole() : Role.valueOf(request.getRole().name());
        boolean newEnabled = request.getEnabled() == null ? user.isEnabled() : request.getEnabled();

        boolean removesAnEnabledAdministrator = user.isEnabled() && user.getRole() == Role.ADMINISTRATEUR
                && (!newEnabled || newRole != Role.ADMINISTRATEUR);
        if (removesAnEnabledAdministrator && userRepository.countByRoleAndEnabledTrue(Role.ADMINISTRATEUR) <= 1) {
            throw new LastAdministratorException();
        }

        boolean changed = newRole != user.getRole() || newEnabled != user.isEnabled();
        user.setRole(newRole);
        user.setEnabled(newEnabled);
        UserEntity saved = userRepository.save(user);
        if (changed) {
            expireSessions(saved.getUsername());
        }
        return toModel(saved);
    }

    @Transactional
    public void resetPassword(UUID userId, ResetPassword request) {
        UserEntity user = loadUser(userId);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        userRepository.save(user);
        expireSessions(user.getUsername());
    }

    private UserEntity loadUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User %s not found".formatted(userId)));
    }

    // Open sessions keep their old authorities: expire them so a changed account takes effect immediately.
    private void expireSessions(String username) {
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (principal instanceof UserDetails details && details.getUsername().equals(username)) {
                sessionRegistry.getAllSessions(principal, false).forEach(SessionInformation::expireNow);
            }
        }
    }

    private static User toModel(UserEntity entity) {
        return new User(entity.getId(), entity.getUsername(),
                com.rfidback.generated.model.Role.fromValue(entity.getRole().name()),
                entity.isEnabled(), entity.getCreationDate());
    }
}
