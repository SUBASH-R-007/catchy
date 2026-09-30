package com.acentra.catchy.telemetry.service;

import com.acentra.catchy.telemetry.config.CatchyProperties;
import com.acentra.catchy.telemetry.domain.UserAccount;
import com.acentra.catchy.telemetry.domain.UserRepository;
import com.acentra.catchy.telemetry.dto.AuthDtos.LoginResponse;
import com.acentra.catchy.telemetry.security.Role;
import com.acentra.catchy.telemetry.security.TokenService;
import com.acentra.catchy.telemetry.web.ApiException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dashboard authentication: BCrypt password login, optional one-click demo login, and startup seeding of the three demo
 * users. Passwords only come from the environment; a user without a configured password can only use demo login.
 */
@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Pattern SAFE_USERNAME = Pattern.compile("^[A-Za-z0-9._-]{1,40}$");

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final AuditService audit;
    private final CatchyProperties props;
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, TokenService tokens, AuditService audit, CatchyProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.audit = audit;
        this.props = props;
        this.dummyHash = encoder.encode("not-a-real-password-" + System.nanoTime()); // equalizes timing for unknown users
    }

    public LoginResponse login(String username, String password) {
        Optional<UserAccount> found = users.findByUsername(username);
        boolean ok;
        if (found.isPresent() && found.get().passwordHash != null) {
            ok = encoder.matches(password, found.get().passwordHash);
        } else {
            encoder.matches(password, dummyHash);
            ok = false;
        }
        if (!ok) {
            String shown = SAFE_USERNAME.matcher(username).matches() ? username : "<invalid>";
            audit.failureAs(new AuditService.Actor(shown, "NONE"), AuditAction.LOGIN_FAILURE, "USER", null, null,
                    "Login failed for user '" + shown + "'");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        return issue(found.get(), "Password login");
    }

    public LoginResponse demoLogin(Role role) {
        if (!props.demoMode()) throw ApiException.notFound("Not found");
        UserAccount user = users.findFirstByRoleOrderById(role.name())
                .orElseThrow(() -> ApiException.notFound("Not found"));
        return issue(user, "Demo login");
    }

    private LoginResponse issue(UserAccount user, String how) {
        Role role = Role.valueOf(user.role);
        TokenService.IssuedToken t = tokens.issue(user.username, role);
        audit.successAs(new AuditService.Actor(user.username, role.name()), AuditAction.LOGIN_SUCCESS, "USER", user.username,
                null, how + " as " + role);
        return new LoginResponse(t.token(), user.username, role, t.expiresAt());
    }

    /** Creates or refreshes the admin / engineer / viewer demo users from the configured passwords. */
    @Transactional
    public void seedUsers() {
        CatchyProperties.Security sec = props.security();
        seed("admin", Role.ADMIN, sec.adminPassword());
        seed("engineer", Role.ENGINEER, sec.engineerPassword());
        seed("viewer", Role.VIEWER, sec.viewerPassword());
    }

    private void seed(String username, Role role, String password) {
        UserAccount u = users.findByUsername(username).orElseGet(() -> {
            UserAccount n = new UserAccount();
            n.username = username;
            return n;
        });
        u.role = role.name();
        boolean hasPassword = password != null && !password.isBlank();
        if (hasPassword) {
            if (u.passwordHash == null || !encoder.matches(password, u.passwordHash)) u.passwordHash = encoder.encode(password);
        } else {
            u.passwordHash = null;
        }
        users.save(u);
        log.info("Dashboard user '{}' ({}): {}", username, role.name().toLowerCase(Locale.ROOT),
                hasPassword ? "password login enabled" : props.demoMode() ? "demo login only (no password configured)"
                        : "no password configured and demo mode is off: cannot sign in");
    }
}
