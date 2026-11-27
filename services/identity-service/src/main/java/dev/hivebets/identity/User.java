package dev.hivebets.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record User(UUID id, String username, List<String> roles, String accountType, Instant createdAt) {}
