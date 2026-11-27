package dev.hivebets.common.web;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** The caller, as told by the gateway's X-User-* headers. */
public record CurrentUser(UUID id, String username, Set<String> roles, String accountType) {
    public static final String H_ID = "X-User-Id";
    public static final String H_NAME = "X-User-Name";
    public static final String H_ROLES = "X-User-Roles";
    public static final String H_TYPE = "X-Account-Type";

    public boolean has(String role) {
        return roles.contains(role);
    }

    public boolean hasAny(String... rs) {
        return Arrays.stream(rs).anyMatch(roles::contains);
    }

    public void require(String... rs) {
        if (!hasAny(rs)) throw ApiException.forbidden("This needs one of the roles " + String.join(", ", rs));
    }

    public static CurrentUser fromHeaders(String id, String name, String roles, String type) {
        if (id == null || id.isBlank()) return null;
        Set<String> r = roles == null || roles.isBlank() ? Set.of()
                : Arrays.stream(roles.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        return new CurrentUser(UUID.fromString(id), name, r, type);
    }
}
