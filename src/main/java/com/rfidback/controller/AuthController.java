package com.rfidback.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.rfidback.generated.api.AuthApiDelegate;
import com.rfidback.generated.model.CurrentUser;
import com.rfidback.generated.model.LoginRequest;
import com.rfidback.service.AuthService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthController implements AuthApiDelegate {

    private final AuthService authService;

    @Override
    public ResponseEntity<CurrentUser> login(LoginRequest loginRequest) {
        ServletRequestAttributes attributes = currentRequest();
        return ResponseEntity.ok(authService.login(loginRequest.getUsername(), loginRequest.getPassword(),
                attributes.getRequest(), attributes.getResponse()));
    }

    @Override
    public ResponseEntity<Void> logout() {
        ServletRequestAttributes attributes = currentRequest();
        authService.logout(attributes.getRequest(), attributes.getResponse());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<CurrentUser> getCurrentUser() {
        return ResponseEntity.ok(authService.currentUser());
    }

    // The generated delegate does not receive the servlet objects needed to manage the session.
    private static ServletRequestAttributes currentRequest() {
        return (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
    }
}
