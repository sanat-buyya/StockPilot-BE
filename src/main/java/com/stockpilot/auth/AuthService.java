package com.stockpilot.auth;

import com.stockpilot.auth.AuthDtos.*;
import com.stockpilot.common.AppException;
import com.stockpilot.common.DbErrors;
import com.stockpilot.common.ErrorCode;
import com.stockpilot.config.AppProperties;
import com.stockpilot.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {
    private record UserRow(UUID id, String email, String name, String passwordHash) {}

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwtEncoder;
    private final AppProperties props;
    private final WorkspaceService workspaces;
    private final TransactionTemplate tx;
    /** Compared against when the email is unknown so login timing does not reveal which emails exist. */
    private final String dummyHash;

    public AuthService(JdbcClient jdbc, PasswordEncoder encoder, JwtEncoder jwtEncoder, AppProperties props,
                       WorkspaceService workspaces, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.jwtEncoder = jwtEncoder;
        this.props = props;
        this.workspaces = workspaces;
        this.tx = tx;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    public SessionDto register(RegisterRequest req) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        String name = req.name().trim();
        if (name.isEmpty()) {
            throw AppException.validation("Validation failed", java.util.List.of(java.util.Map.of("field", "name", "message", "Name is required")));
        }
        if (req.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw AppException.validation("Validation failed",
                    java.util.List.of(java.util.Map.of("field", "password", "message", "Password must be at most 72 bytes")));
        }
        String hash = encoder.encode(req.password());
        String workspaceName = req.workspaceName() == null || req.workspaceName().isBlank()
                ? name + "'s workspace" : req.workspaceName().trim();
        try {
            UUID userId = tx.execute(s -> {
                UUID id = jdbc.sql("INSERT INTO users (email, name, password_hash) VALUES (:e, :n, :h) RETURNING id")
                        .param("e", email).param("n", name).param("h", hash)
                        .query(UUID.class).single();
                workspaces.createInternal(id, workspaceName);
                return id;
            });
            return session(new UserRow(userId, email, name, hash));
        } catch (RuntimeException e) {
            if (DbErrors.uniqueViolation(e) != null) {
                throw new AppException(ErrorCode.EMAIL_TAKEN, "An account with this email already exists");
            }
            throw e;
        }
    }

    public SessionDto login(LoginRequest req) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        Optional<UserRow> user = findByEmail(email);
        boolean ok = encoder.matches(req.password(), user.map(UserRow::passwordHash).orElse(dummyHash));
        if (user.isEmpty() || !ok) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Invalid email or password");
        }
        return session(user.get());
    }

    public MeDto me(UUID userId) {
        UserRow u = jdbc.sql("SELECT id, email, name, password_hash FROM users WHERE id = :id")
                .param("id", userId).query(AuthService::map).optional()
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED, "Authentication required"));
        return new MeDto(new UserDto(u.id(), u.email(), u.name()), workspaces.listForUser(userId));
    }

    private Optional<UserRow> findByEmail(String email) {
        return jdbc.sql("SELECT id, email, name, password_hash FROM users WHERE email = :e")
                .param("e", email).query(AuthService::map).optional();
    }

    private static UserRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new UserRow(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("name"), rs.getString("password_hash"));
    }

    private SessionDto session(UserRow u) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("stockpilot")
                .subject(u.id().toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(props.jwt().expiresMinutes())))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new SessionDto(token, new UserDto(u.id(), u.email(), u.name()), workspaces.listForUser(u.id()));
    }
}
