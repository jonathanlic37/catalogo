// consumer-api/src/main/java/com/catalogo/consumer/security/BearerTokenFilter.java
package com.catalogo.consumer.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Autentica con un Bearer token estático comparado en tiempo constante. */
public class BearerTokenFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";
    private final byte[] expected;

    public BearerTokenFilter(String expectedToken) {
        this.expected = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(PREFIX)) {
            byte[] given = header.substring(PREFIX.length()).trim().getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(given, expected)) {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken("frontend", null,
                                List.of(new SimpleGrantedAuthority("ROLE_CLIENT"))));
            }
        }
        chain.doFilter(request, response);
    }
}
