package dev.hivebets.gateway;

import java.util.Set;
import org.springframework.http.HttpMethod;

/** Which roles a path needs. Kept in one place so it is easy to read and test. See docs/API.md. */
public final class AccessRules {
    private AccessRules() {}

    public enum Need { PUBLIC, SIGNED_IN, ADMIN_OR_ENGINE, ADMIN }

    public static Need need(HttpMethod method, String path) {
        if (method == HttpMethod.OPTIONS) return Need.PUBLIC;
        if (path.equals("/api/auth/signup") || path.equals("/api/auth/login") || path.equals("/api/auth/logout")
                || path.equals("/api/events") || path.equals("/api/live/stats") || path.startsWith("/actuator/health")) {
            return Need.PUBLIC;
        }
        if (path.startsWith("/api/analytics") || path.startsWith("/api/admin")) return Need.ADMIN;
        if (method == HttpMethod.POST && (path.equals("/api/markets") || path.matches("^/api/markets/[^/]+/resolve$"))) {
            return Need.ADMIN_OR_ENGINE;
        }
        if (method == HttpMethod.POST && path.equals("/api/news")) return Need.ADMIN_OR_ENGINE;
        return Need.SIGNED_IN;
    }

    public static boolean allows(Need need, Set<String> roles) {
        return switch (need) {
            case PUBLIC, SIGNED_IN -> true;
            case ADMIN_OR_ENGINE -> roles.contains("ADMIN") || roles.contains("ENGINE");
            case ADMIN -> roles.contains("ADMIN");
        };
    }
}
