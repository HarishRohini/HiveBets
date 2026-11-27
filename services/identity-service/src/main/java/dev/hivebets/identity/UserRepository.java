package dev.hivebets.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(User user, String passwordHash) {}

    private static Row map(ResultSet rs, int i) throws SQLException {
        User u = new User(rs.getObject("id", UUID.class), rs.getString("username"),
                Arrays.asList(rs.getString("roles").split(",")), rs.getString("account_type"),
                rs.getTimestamp("created_at").toInstant());
        return new Row(u, rs.getString("password_hash"));
    }

    public Optional<Row> findByUsername(String username) {
        return jdbc.query("select * from identity.users where username = ?", UserRepository::map, username)
                .stream().findFirst();
    }

    public Optional<User> findById(UUID id) {
        return jdbc.query("select * from identity.users where id = ?", UserRepository::map, id)
                .stream().findFirst().map(Row::user);
    }

    /** Inserts a user; returns empty if the username is taken. */
    public Optional<User> insert(String username, String hash, List<String> roles, String type) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        int n = jdbc.update("insert into identity.users (id, username, password_hash, roles, account_type, created_at)"
                        + " values (?, ?, ?, ?, ?, ?) on conflict (username) do nothing",
                id, username, hash, String.join(",", roles), type, Timestamp.from(now));
        return n == 1 ? Optional.of(new User(id, username, roles, type, now)) : Optional.empty();
    }

    public void updatePasswordAndRoles(UUID id, String hash, List<String> roles) {
        jdbc.update("update identity.users set password_hash = ?, roles = ? where id = ?", hash, String.join(",", roles), id);
    }
}
