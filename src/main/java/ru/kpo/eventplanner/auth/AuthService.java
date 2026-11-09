package ru.kpo.eventplanner.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService implements UserDetailsService {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;

    public AuthService(JdbcTemplate db, PasswordEncoder passwords) {
        this.db = db;
        this.passwords = passwords;
    }

    public Account register(String email, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Пароль слишком длинный. Используйте более короткий пароль.");
        String normalized = normalizeEmail(email);
        String hash = passwords.encode(password);
        try {
            return db.queryForObject("""
                    INSERT INTO users(email,password_hash) VALUES (?,?) RETURNING id,email
                    """, (rs, row) -> new Account(rs.getObject("id", UUID.class), rs.getString("email")),
                    normalized, hash);
        } catch (DuplicateKeyException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Пользователь с таким email уже зарегистрирован.");
        }
    }

    @Override
    public AccountPrincipal loadUserByUsername(String email) {
        var users = db.query("SELECT id,email,password_hash FROM users WHERE email=?",
                (rs, row) -> new AccountPrincipal(rs.getObject("id", UUID.class),
                        rs.getString("email"), rs.getString("password_hash")), normalizeEmail(email));
        if (users.isEmpty()) throw new UsernameNotFoundException("Invalid credentials");
        return users.get(0);
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    public static Account account(AccountPrincipal user) {
        return new Account(user.getId(), user.getUsername());
    }

    public record Account(UUID id, String email) { }
}
