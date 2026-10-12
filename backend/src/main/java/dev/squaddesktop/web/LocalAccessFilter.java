package dev.squaddesktop.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Restringe o serviço local ao próprio aplicativo.
 *
 * <ul>
 *   <li>Host precisa ser loopback (evita ataques de DNS rebinding a partir de páginas abertas no navegador).</li>
 *   <li>Origin, quando presente, precisa ser a do próprio serviço ou uma origem de desenvolvimento configurada.</li>
 *   <li>Toda chamada a /api exige o token desta inicialização: no cabeçalho Authorization (Bearer) ou no cookie
 *       HttpOnly que o shell desktop grava na sessão da janela. O token nunca aparece em URLs.</li>
 *   <li>Com autenticação por cookie, métodos que alteram dados exigem o cabeçalho X-Squad-Client, que um
 *       formulário de outro site não consegue enviar.</li>
 * </ul>
 */
public class LocalAccessFilter extends OncePerRequestFilter {

    public static final String COOKIE = "squad_token";
    public static final String CLIENT_HEADER = "X-Squad-Client";

    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
            + "font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'";

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD");

    private final byte[] token;
    private final List<String> allowedOrigins;

    public LocalAccessFilter(String token, List<String> allowedOrigins) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!loopbackHost(request.getHeader("Host"))) {
            deny(response, HttpServletResponse.SC_FORBIDDEN, "host_not_allowed", "Acesso permitido apenas pelo próprio computador.");
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !sameOrigin(origin, request) && !allowedOrigins.contains(origin)) {
            deny(response, HttpServletResponse.SC_FORBIDDEN, "origin_not_allowed", "Origem não autorizada.");
            return;
        }
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            response.setHeader("Content-Security-Policy", CSP);
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Referrer-Policy", "no-referrer");
            chain.doFilter(request, response);
            return;
        }
        String bearer = bearer(request.getHeader("Authorization"));
        String cookie = cookie(request);
        boolean viaBearer = bearer != null && matches(bearer);
        boolean viaCookie = !viaBearer && cookie != null && matches(cookie);
        if (!viaBearer && !viaCookie) {
            deny(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", "Sessão local inválida. Reabra o aplicativo.");
            return;
        }
        if (viaCookie && !SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT)) && request.getHeader(CLIENT_HEADER) == null) {
            deny(response, HttpServletResponse.SC_FORBIDDEN, "client_header_required", "Requisição sem o cabeçalho do aplicativo.");
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        chain.doFilter(request, response);
    }

    private boolean matches(String candidate) {
        return MessageDigest.isEqual(token, candidate.getBytes(StandardCharsets.UTF_8));
    }

    static boolean loopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String name = host.startsWith("[") ? host.substring(0, host.indexOf(']') + 1) : host.split(":")[0];
        return LOOPBACK.contains(name.toLowerCase(Locale.ROOT));
    }

    private static boolean sameOrigin(String origin, HttpServletRequest request) {
        String host = request.getHeader("Host");
        return origin.equalsIgnoreCase("http://" + host);
    }

    private static String bearer(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return header.substring(7).strip();
    }

    private static String cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static void deny(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
