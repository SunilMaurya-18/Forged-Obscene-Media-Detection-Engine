package com.forgeddetect.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeddetect.observability.FmdMetrics;
import com.forgeddetect.repository.ModeratorAccountRepository;
import com.forgeddetect.service.ApiKeyService;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Phase 8–9: HTTP Basic + session auth; MODERATOR role for queue/decision.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({DevModeratorProperties.class, ApiProperties.class})
public class SecurityConfig {

    /**
     * Phase 16: third-party API. Stateless, API key only (no Basic, no session), so a moderator
     * cookie can never be used against it and a key can never reach moderator endpoints.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiV1FilterChain(
            HttpSecurity http, ApiKeyService keys, RateLimiter rateLimiter, ObjectMapper mapper, FmdMetrics metrics)
            throws Exception {
        http
                .securityMatcher("/api/v1/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .addFilterBefore(new ApiKeyAuthFilter(keys, rateLimiter, mapper, metrics), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, e) -> {
                    res.setStatus(HttpStatus.UNAUTHORIZED.value());
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    res.getWriter().write("{\"error\":\"invalid_api_key\",\"message\":\"API key required.\"}");
                }))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().hasRole("API_CLIENT"));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .httpBasic(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // Aggregate counters and latencies only. Compose serves actuator on an unpublished
                        // management port (MANAGEMENT_SERVER_PORT) so only Prometheus on the internal network reaches it.
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/analyze").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/stats").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/verify/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/verify").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/prescreen").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/verify-report/**").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/queue/**").hasRole("MODERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/compliance/complaints").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/complaints").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/complaints/*/status").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/appeals").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/appeals/*/status").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/transparency").permitAll()
                        // Phase 25: 404 unless demo.enabled; privileged demo actions check roles in DemoController
                        .requestMatchers("/api/demo", "/api/demo/**").permitAll()
                        .requestMatchers("/api/appeals/**", "/api/moderation/**").hasRole("MODERATOR")
                        .requestMatchers("/api/compliance/**").hasRole("MODERATOR")
                        .requestMatchers("/api/admin/signing-keys/**").hasRole("SENIOR_MODERATOR")
                        .requestMatchers("/api/admin/**").hasRole("MODERATOR")
                        .anyRequest().authenticated()
                );
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
                "http://localhost:3000",
                "http://localhost:3001",
                "http://localhost:5173",
                "http://127.0.0.1:3000",
                "http://127.0.0.1:3001",
                "http://127.0.0.1:5173"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Moderators and their roles come from {@code moderator_accounts}, seeded by {@code ModeratorAccountSeeder}. */
    @Bean
    public UserDetailsService userDetailsService(ModeratorAccountRepository accounts) {
        return new ModeratorUserDetailsService(accounts);
    }
}
