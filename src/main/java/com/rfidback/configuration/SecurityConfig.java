package com.rfidback.configuration;

import static org.springframework.security.config.Customizer.withDefaults;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.ConcurrentSessionControlAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import com.rfidback.entity.Role;
import com.rfidback.security.AppUserDetailsService;
import com.rfidback.security.CsrfCookieFilter;
import com.rfidback.security.ReaderApiTokenAuthenticationFilter;

import jakarta.servlet.DispatcherType;

@Configuration
public class SecurityConfig {

    private static final String READER_SCAN_PATH = "/api/tags/scan";
    private static final String READER_REGISTRATION_READS_PATH = "/api/tags/registration-reads";
    private static final String READER_TOKEN_HEADER = "x-api-token";
    private static final String LINE_ACTIVITY_PATH = "/api/lines/*/current-activity";
    private static final String ADMINISTRATEUR = Role.ADMINISTRATEUR.name();
    private static final String OPERATEUR = Role.OPERATEUR.name();

    @Value("${app.security.allow-h2-console:false}")
    private boolean allowH2Console;

    // Readers and human users are authenticated differently. Readers send a stateless API token: reader devices on
    // /api/tags/scan and /api/tags/registration-reads (spec 011), and the line kiosk (reader.html on the line's touch
    // screen) on its own reader's records. Users hold a server-side session protected by CSRF. Each gets its own chain.
    @Bean
    @Order(1)
    public SecurityFilterChain readerSecurityFilterChain(HttpSecurity http,
            ReaderApiTokenAuthenticationFilter readerApiTokenAuthenticationFilter) throws Exception {
        http
                .securityMatcher(new OrRequestMatcher(path(null, READER_SCAN_PATH),
                        path(null, READER_REGISTRATION_READS_PATH)))
                .cors(withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(readerApiTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(path(HttpMethod.OPTIONS, READER_SCAN_PATH)).permitAll()
                        .requestMatchers(path(HttpMethod.OPTIONS, READER_REGISTRATION_READS_PATH)).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());
        return http.build();
    }

    // Any other /api/** request carrying x-api-token is the line kiosk (spec 008, FR-005a, research R11). It only
    // opens the routes of reader.html: its reader's records and conformity, and its line's current activity (spec
    // 012); RecordService and LineActivityService limit them to the token's own reader. No session, no CSRF: a
    // third-party page cannot make a browser send a custom header.
    @Bean
    @Order(2)
    public SecurityFilterChain kioskSecurityFilterChain(HttpSecurity http,
            ReaderApiTokenAuthenticationFilter readerApiTokenAuthenticationFilter) throws Exception {
        http
                .securityMatcher(new AndRequestMatcher(path(null, "/api/**"),
                        new RequestHeaderRequestMatcher(READER_TOKEN_HEADER)))
                .cors(withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .addFilterBefore(readerApiTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(path(HttpMethod.OPTIONS, "/**")).permitAll()
                        .requestMatchers(path(HttpMethod.GET, "/api/records/readers/*")).authenticated()
                        .requestMatchers(path(HttpMethod.PATCH, "/api/records/*/conformity")).authenticated()
                        .requestMatchers(path(HttpMethod.GET, LINE_ACTIVITY_PATH)).authenticated()
                        .requestMatchers(path(HttpMethod.PUT, LINE_ACTIVITY_PATH)).authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain userSecurityFilterChain(HttpSecurity http,
            CookieCsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository securityContextRepository,
            SessionRegistry sessionRegistry) throws Exception {
        http
                .cors(withDefaults())
                .csrf(csrf -> {
                    csrf.csrfTokenRepository(csrfTokenRepository)
                            .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                            .ignoringRequestMatchers(path(HttpMethod.POST, "/api/auth/login"));
                    if (allowH2Console) {
                        csrf.ignoringRequestMatchers(path(null, "/h2-console/**"));
                    }
                })
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId())
                        .sessionConcurrency(concurrency -> concurrency
                                .maximumSessions(-1)
                                .sessionRegistry(sessionRegistry)
                                .expiredSessionStrategy(event -> event.getResponse()
                                        .setStatus(HttpStatus.UNAUTHORIZED.value()))))
                // Anonymous 401s must not create a session just to remember the request.
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> {
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                            .requestMatchers(path(HttpMethod.OPTIONS, "/**")).permitAll()
                            .requestMatchers(path(HttpMethod.POST, "/api/auth/login")).permitAll()
                            .requestMatchers(path(HttpMethod.GET, "/actuator/health")).permitAll();
                    if (allowH2Console) {
                        auth.requestMatchers(path(null, "/h2-console/**")).permitAll();
                    }
                    // Access matrix of spec 008, most specific rules first; anything not listed is denied.
                    auth.requestMatchers(path(null, "/api/users/**")).hasRole(ADMINISTRATEUR)
                            .requestMatchers(path(HttpMethod.GET, "/api/pickers")).hasAnyRole(ADMINISTRATEUR, OPERATEUR)
                            .requestMatchers(path(HttpMethod.GET, "/api/pickers/*")).hasAnyRole(ADMINISTRATEUR, OPERATEUR)
                            .requestMatchers(path(null, "/api/pickers/**")).hasRole(ADMINISTRATEUR)
                            .requestMatchers(path(HttpMethod.GET, "/api/readers")).hasAnyRole(ADMINISTRATEUR, OPERATEUR)
                            .requestMatchers(path(null, "/api/readers/**")).hasRole(ADMINISTRATEUR)
                            .requestMatchers(path(null, "/api/tags/**")).hasRole(ADMINISTRATEUR)
                            .requestMatchers(path(null, "/api/buckets/**")).hasRole(ADMINISTRATEUR)
                            .requestMatchers(path(HttpMethod.GET, "/api/records/*/conformity-history"))
                            .hasRole(ADMINISTRATEUR) // spec 005, FR-007
                            .requestMatchers(path(HttpMethod.GET, "/api/activities")).hasAnyRole(ADMINISTRATEUR, OPERATEUR)
                            .requestMatchers(path(null, "/api/activities/**")).hasRole(ADMINISTRATEUR)
                            .requestMatchers(path(null, LINE_ACTIVITY_PATH)).hasAnyRole(ADMINISTRATEUR, OPERATEUR)
                            .requestMatchers(path(null, "/api/records/**")).hasAnyRole(ADMINISTRATEUR, OPERATEUR)
                            .requestMatchers(path(null, "/api/auth/**")).authenticated()
                            .anyRequest().denyAll();
                })
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .headers(headers -> {
                    if (allowH2Console) {
                        headers.frameOptions(frameOptions -> frameOptions.disable());
                    }
                });
        return http.build();
    }

    @Bean
    public FilterRegistrationBean<ReaderApiTokenAuthenticationFilter> readerApiTokenFilterRegistration(
            ReaderApiTokenAuthenticationFilter readerApiTokenAuthenticationFilter) {
        // The filter is a @Component: keep Spring Boot from also running it as a global servlet filter.
        FilterRegistrationBean<ReaderApiTokenAuthenticationFilter> registration =
                new FilterRegistrationBean<>(readerApiTokenAuthenticationFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public AuthenticationManager authenticationManager(AppUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    // What formLogin would apply automatically; the JSON login endpoint must call it explicitly.
    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy(SessionRegistry sessionRegistry,
            CookieCsrfTokenRepository csrfTokenRepository) {
        ConcurrentSessionControlAuthenticationStrategy concurrentSessions =
                new ConcurrentSessionControlAuthenticationStrategy(sessionRegistry);
        concurrentSessions.setMaximumSessions(-1);
        CsrfAuthenticationStrategy csrfStrategy = new CsrfAuthenticationStrategy(csrfTokenRepository);
        csrfStrategy.setRequestHandler(new CsrfTokenRequestAttributeHandler());
        return new CompositeSessionAuthenticationStrategy(List.of(
                concurrentSessions,
                new ChangeSessionIdAuthenticationStrategy(),
                new RegisterSessionAuthenticationStrategy(sessionRegistry),
                csrfStrategy));
    }

    private static RequestMatcher path(HttpMethod method, String pattern) {
        return method == null
                ? PathPatternRequestMatcher.withDefaults().matcher(pattern)
                : PathPatternRequestMatcher.withDefaults().matcher(method, pattern);
    }
}
