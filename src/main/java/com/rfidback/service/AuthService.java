package com.rfidback.service;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Service;

import com.rfidback.entity.UserEntity;
import com.rfidback.exception.InvalidCredentialsException;
import com.rfidback.generated.model.CurrentUser;
import com.rfidback.generated.model.Role;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String ROLE_PREFIX = "ROLE_";

    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;

    public CurrentUser login(String username, String password, HttpServletRequest request,
            HttpServletResponse response) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(UsernamePasswordAuthenticationToken
                    .unauthenticated(UserEntity.normalizeUsername(username), password));
        } catch (AuthenticationException exception) {
            throw new InvalidCredentialsException();
        }

        // Session id change, session registry and CSRF token rotation, as formLogin would do.
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        // The rotated CSRF token is lazy: load it so the new XSRF-TOKEN cookie is part of this response.
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        return toCurrentUser(authentication);
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response,
                SecurityContextHolder.getContext().getAuthentication());
    }

    public CurrentUser currentUser() {
        return toCurrentUser(SecurityContextHolder.getContext().getAuthentication());
    }

    private CurrentUser toCurrentUser(Authentication authentication) {
        String authority = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(value -> value.startsWith(ROLE_PREFIX))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Authenticated user has no role"));
        return new CurrentUser(authentication.getName(), Role.fromValue(authority.substring(ROLE_PREFIX.length())));
    }
}
