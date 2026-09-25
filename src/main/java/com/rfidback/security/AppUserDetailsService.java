package com.rfidback.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.rfidback.entity.UserEntity;
import com.rfidback.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) {
        UserEntity user = userRepository.findByUsername(UserEntity.normalizeUsername(username))
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(user.getRole().authority())
                .disabled(!user.isEnabled())
                .build();
    }
}
