package com.rfidback.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.rfidback.security.ReaderApiTokenAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final ReaderApiTokenAuthenticationFilter readerApiTokenAuthenticationFilter;

    @Value("${app.security.allow-h2-console:false}")
    private boolean allowH2Console;

    public SecurityConfig(ReaderApiTokenAuthenticationFilter readerApiTokenAuthenticationFilter) {
        this.readerApiTokenAuthenticationFilter = readerApiTokenAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // pas nécessaire pour API REST
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/tags/scan").authenticated()
                        .anyRequest().permitAll()
                )
                .addFilterBefore(readerApiTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable()) // désactive la mire
                .httpBasic(basic -> basic.disable()) // désactive le basic auth
                .headers(headers -> {
                    if (allowH2Console) {
                        headers.frameOptions(frameOptions -> frameOptions.disable());
                    }
                });

        return http.build();
    }
}
