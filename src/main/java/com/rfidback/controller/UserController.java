package com.rfidback.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.rfidback.generated.api.UserApiDelegate;
import com.rfidback.generated.model.CreateUser;
import com.rfidback.generated.model.ResetPassword;
import com.rfidback.generated.model.UpdateUser;
import com.rfidback.generated.model.User;
import com.rfidback.generated.model.UsersList;
import com.rfidback.service.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserController implements UserApiDelegate {

    private final UserService userService;

    @Override
    public ResponseEntity<UsersList> listUsers() {
        return ResponseEntity.ok(userService.listUsers());
    }

    @Override
    public ResponseEntity<User> createUser(CreateUser createUser) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.createUser(createUser));
    }

    @Override
    public ResponseEntity<User> updateUser(UUID userId, UpdateUser updateUser) {
        return ResponseEntity.ok(userService.updateUser(userId, updateUser));
    }

    @Override
    public ResponseEntity<Void> resetUserPassword(UUID userId, ResetPassword resetPassword) {
        userService.resetPassword(userId, resetPassword);
        return ResponseEntity.noContent().build();
    }
}
