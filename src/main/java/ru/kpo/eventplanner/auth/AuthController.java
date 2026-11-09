package ru.kpo.eventplanner.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService users;

    public AuthController(AuthService users) { this.users = users; }

    @GetMapping("/csrf")
    public ResponseEntity<Map<String, String>> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("headerName", token.getHeaderName(), "token", token.getToken()));
    }

    @PostMapping("/register")
    public ResponseEntity<AuthService.Account> register(@Valid @RequestBody Registration request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(users.register(request.email(), request.password()));
    }

    @GetMapping("/me")
    public ResponseEntity<AuthService.Account> me(@AuthenticationPrincipal AccountPrincipal user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AuthService.account(user));
    }

    public record Registration(
            @NotBlank(message="Введите email.") @Email(message="Введите корректный email.")
            @Size(max=320, message="Email слишком длинный.") String email,
            @NotNull(message="Введите пароль.")
            @Size(min=8, max=72, message="Пароль должен содержать от 8 до 72 символов.") String password) {
        public Registration { email = AuthService.normalizeEmail(email); }
        @Override public String toString() { return "Registration[credentials=REDACTED]"; }
    }
}
