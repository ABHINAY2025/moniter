package com.employeemonitor.api.auth;

import com.employeemonitor.api.config.AppProperties;
import com.employeemonitor.api.users.Permissions;
import com.employeemonitor.api.users.Role;
import com.employeemonitor.api.users.RoleRepository;
import com.employeemonitor.api.users.User;
import com.employeemonitor.api.users.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * Creates the first SUPER_ADMIN from BOOTSTRAP_ADMIN_* environment variables when no users exist.
 * There are no built-in default credentials.
 */
@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties.Bootstrap props;

    public BootstrapAdminInitializer(UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
                                     AppProperties.Bootstrap props) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        if (!StringUtils.hasText(props.adminUsername()) || !StringUtils.hasText(props.adminPassword())) {
            log.warn("No users exist. Set BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_EMAIL and "
                    + "BOOTSTRAP_ADMIN_PASSWORD to create the first SUPER_ADMIN.");
            return;
        }
        if (props.adminPassword().length() < 12) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD must be at least 12 characters");
        }
        Role superAdmin = roles.findByName(Permissions.ROLE_SUPER_ADMIN).orElseThrow();
        String email = StringUtils.hasText(props.adminEmail()) ? props.adminEmail()
                : props.adminUsername() + "@localhost";
        User admin = new User(props.adminUsername(), email, "Administrator",
                passwordEncoder.encode(props.adminPassword()));
        admin.setRoles(Set.of(superAdmin));
        users.save(admin);
        log.info("Bootstrap SUPER_ADMIN '{}' created. Change the password after first login.", props.adminUsername());
    }
}
