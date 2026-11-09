package ru.kpo.eventplanner.auth;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

// Spring Security erases the inherited password after successful authentication.
public class AccountPrincipal extends User {
    private static final long serialVersionUID = 1L;
    private final UUID id;

    public AccountPrincipal(UUID id, String email, String passwordHash) {
        super(email, passwordHash, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        this.id = id;
    }

    public UUID getId() { return id; }
}
