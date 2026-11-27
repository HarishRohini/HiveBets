package dev.hivebets.identity;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Creates (or refreshes the password of) the admin and the world-engine service account at boot. */
@Component
public class AccountSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AccountSeeder.class);
    private final UserRepository users;
    private final AuthService auth;
    private final String adminUser, adminPass, engineUser, enginePass;

    public AccountSeeder(UserRepository users, AuthService auth,
                         @Value("${hivebets.admin.username}") String adminUser,
                         @Value("${hivebets.admin.password}") String adminPass,
                         @Value("${hivebets.engine.username}") String engineUser,
                         @Value("${hivebets.engine.password}") String enginePass) {
        this.users = users;
        this.auth = auth;
        this.adminUser = adminUser;
        this.adminPass = adminPass;
        this.engineUser = engineUser;
        this.enginePass = enginePass;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed(adminUser, adminPass, List.of("ADMIN", "TRADER"), "HUMAN");
        seed(engineUser, enginePass, List.of("ENGINE"), "SERVICE");
    }

    private void seed(String name, String pass, List<String> roles, String type) {
        var existing = users.findByUsername(name);
        if (existing.isPresent()) {
            if (!auth.encoder.matches(pass, existing.get().passwordHash())
                    || !existing.get().user().roles().equals(roles)) {
                users.updatePasswordAndRoles(existing.get().user().id(), auth.encoder.encode(pass), roles);
                log.info("updated seeded account {}", name);
            }
            return;
        }
        users.insert(name, auth.encoder.encode(pass), roles, type);
        log.info("created seeded account {} with roles {}", name, roles);
    }
}
