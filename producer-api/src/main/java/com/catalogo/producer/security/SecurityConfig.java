// producer-api/src/main/java/com/catalogo/producer/security/SecurityConfig.java
package com.catalogo.producer.security;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Dos credenciales con privilegios distintos (mínimo privilegio):
 * <ul>
 *   <li><b>SERVICE</b> ({@code CONSUMER_TO_PRODUCER_TOKEN}): la que tiene el Consumer. Solo puede
 *       enviar el webhook y leer (reconciliación). No puede escribir directamente en la fuente de
 *       verdad: todo cambio del Consumer entra validado por el webhook.</li>
 *   <li><b>ADMIN</b> ({@code PRODUCER_ADMIN_TOKEN}): la de la aplicación central. Puede leer y
 *       crear/editar/borrar ítems directamente, y ver métricas. No puede suplantar al Consumer en el
 *       webhook.</li>
 * </ul>
 * Rotación sin corte: cada credencial admite un token anterior opcional ({@code *_PREVIOUS}) que se
 * acepta con el mismo rol mientras los clientes pasan al nuevo; después se vacía.
 */
@Configuration
public class SecurityConfig {

    static final String SERVICE = "SERVICE";
    static final String ADMIN = "ADMIN";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    @Value("${app.security.consumer-token}") String consumerToken,
                                    @Value("${app.security.admin-token}") String adminToken,
                                    @Value("${app.security.consumer-token-previous:}") String consumerPrevious,
                                    @Value("${app.security.admin-token-previous:}") String adminPrevious) throws Exception {
        List<Map.Entry<String, String>> tokens = new ArrayList<>();
        tokens.add(Map.entry(SERVICE, TokenValidator.requireStrong("CONSUMER_TO_PRODUCER_TOKEN", consumerToken)));
        tokens.add(Map.entry(ADMIN, TokenValidator.requireStrong("PRODUCER_ADMIN_TOKEN", adminToken)));
        if (!consumerPrevious.isBlank()) {
            tokens.add(Map.entry(SERVICE, TokenValidator.requireStrong("CONSUMER_TO_PRODUCER_TOKEN_PREVIOUS", consumerPrevious)));
        }
        if (!adminPrevious.isBlank()) {
            tokens.add(Map.entry(ADMIN, TokenValidator.requireStrong("PRODUCER_ADMIN_TOKEN_PREVIOUS", adminPrevious)));
        }
        TokenValidator.requireAllDistinct(tokens.stream().map(Map.Entry::getValue).toList());
        TokenValidator.warnRotationWindow(consumerPrevious, adminPrevious);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/webhooks/catalogo").hasRole(SERVICE)
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole(SERVICE, ADMIN)
                        .requestMatchers("/api/**").hasRole(ADMIN)
                        .requestMatchers("/actuator/prometheus").hasRole(ADMIN)
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> problem(res, 401, "Unauthorized",
                                "Token ausente o inválido"))
                        .accessDeniedHandler((req, res, ex) -> problem(res, 403, "Forbidden",
                                "La credencial no tiene permiso para esta operación")))
                .addFilterBefore(new BearerTokenFilter(tokens), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void problem(jakarta.servlet.http.HttpServletResponse res, int status, String title, String detail)
            throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\"}".formatted(title, status, detail));
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") String origins) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Event-Type"));
        cfg.setExposedHeaders(List.of("Idempotent-Replayed"));
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
