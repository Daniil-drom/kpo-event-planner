package ru.kpo.eventplanner.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12) {
            @Override public boolean matches(CharSequence raw, String hash) {
                // BCrypt uses at most 72 UTF-8 bytes; never silently accept a longer password.
                return raw != null && raw.toString().getBytes(StandardCharsets.UTF_8).length <= 72
                        && super.matches(raw, hash);
            }
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json) throws Exception {
        http
                .authorizeHttpRequests(access -> access
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/app.js", "/auth.js", "/visits.js", "/styles.css",
                                "/favicon.ico", "/actuator/health", "/api/cities", "/api/categories",
                                "/api/events", "/api/events/*", "/api/catalog/status", "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/logout").permitAll()
                        .requestMatchers("/api/auth/me", "/api/visits", "/api/visits/**").authenticated()
                        .anyRequest().denyAll())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) ->
                                message(json, response, 401, "Войдите в аккаунт."))
                        .accessDeniedHandler((request, response, error) ->
                                message(json, response, 403, "Не удалось подтвердить запрос. Обновите страницу и повторите действие.")))
                .formLogin(form -> form
                        .loginPage("/").loginProcessingUrl("/api/auth/login").usernameParameter("email")
                        .successHandler((request, response, authentication) -> {
                            response.setContentType("application/json;charset=UTF-8");
                            json.writeValue(response.getWriter(), AuthService.account((AccountPrincipal) authentication.getPrincipal()));
                        })
                        .failureHandler((request, response, error) ->
                                message(json, response, 401, "Неверный email или пароль.")))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)));
        // Keep Spring Security's default session-backed CSRF protection, including login and logout.
        return http.build();
    }

    private static void message(ObjectMapper json, HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getWriter(), Map.of("message", message));
    }
}
