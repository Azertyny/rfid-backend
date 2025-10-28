package com.rfidback.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // pas nécessaire pour API REST
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll() // autorise tout pour le moment
                )
                .formLogin(form -> form.disable()) // désactive la mire
                .httpBasic(basic -> basic.disable()) // désactive le basic auth
                .headers(headers -> headers.frameOptions(frameOptions -> frameOptions.disable())); // permet l'accès à
                                                                                                   // H2 console

        return http.build();
    }
}
