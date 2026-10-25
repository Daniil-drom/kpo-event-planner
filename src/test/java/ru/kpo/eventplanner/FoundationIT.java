package ru.kpo.eventplanner;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FoundationIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;

    @Test
    void exposesSeededCitiesThroughHttp() {
        var response = http.getForEntity("/api/cities", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("spb", "msk", "ekb", "Europe/Moscow", "Asia/Yekaterinburg");
    }

    @Test
    @Transactional
    void acceptsWishWithoutDates() {
        var user = createUser();
        long event = createEvent();
        jdbc.update("INSERT INTO user_visits(user_id, event_id, status) VALUES (?, ?, 'WANT_TO_VISIT')",
                user, event);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_visits WHERE user_id = ?",
                Long.class, user)).isEqualTo(1L);
    }

    @Test
    @Transactional
    void rejectsPlannedVisitWithoutDates() {
        var user = createUser();
        long event = createEvent();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO user_visits(user_id, event_id, status) VALUES (?, ?, 'PLANNED')", user, event))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsReversedInterval() {
        var user = createUser();
        long event = createEvent();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO user_visits(user_id, event_id, status, start_at, end_at)
                VALUES (?, ?, 'PLANNED', '2026-10-28T15:00:00Z', '2026-10-28T14:00:00Z')
                """, user, event)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void cannotDeleteEventWithPersonalPlan() {
        var user = createUser();
        long event = createEvent();
        jdbc.update("INSERT INTO user_visits(user_id, event_id, status) VALUES (?, ?, 'WANT_TO_VISIT')",
                user, event);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM events WHERE id = ?", event))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID createUser() {
        return jdbc.queryForObject("""
                INSERT INTO users(email, password_hash) VALUES (?, 'test-hash')
                RETURNING id
                """, UUID.class, UUID.randomUUID() + "@example.test");
    }

    private long createEvent() {
        return jdbc.queryForObject("""
                INSERT INTO events(kudago_id, city_id, title, site_url)
                VALUES (900000001, (SELECT id FROM cities WHERE kudago_slug = 'spb'),
                        'Test event', 'https://example.test/event')
                RETURNING id
                """, Long.class);
    }
}
