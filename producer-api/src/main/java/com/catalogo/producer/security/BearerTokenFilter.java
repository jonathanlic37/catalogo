// producer-api/src/main/java/com/catalogo/producer/security/BearerTokenFilter.java
package com.catalogo.producer.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autentica con Bearer tokens estáticos, cada uno asociado a un rol (p. ej. SERVICE para el
 * Consumer, ADMIN para la administración directa). La comparación es en tiempo constante y se
 * evalúan todos los tokens, de modo que el tiempo de respuesta no revela cuál coincidió.
 */
public class BearerTokenFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";
    private final Map<String, byte[]> tokensByRole = new LinkedHashMap<>();

    /** @param tokensByRole rol (sin prefijo ROLE_) → token esperado */
    public BearerTokenFilter(Map<String, String> tokensByRole) {
        tokensByRole.forEach((role, token) -> this.tokensByRole.put(role, token.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(PREFIX)) {
            byte[] given = header.substring(PREFIX.length()).trim().getBytes(StandardCharsets.UTF_8);
            String matchedRole = null;
            for (Map.Entry<String, byte[]> e : tokensByRole.entrySet()) {
                if (MessageDigest.isEqual(given, e.getValue()) && matchedRole == null) {
                    matchedRole = e.getKey();
                }
            }
            if (matchedRole != null) {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(matchedRole.toLowerCase(), null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + matchedRole))));
            }
        }
        chain.doFilter(request, response);
    }
}
