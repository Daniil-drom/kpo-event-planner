package ru.kpo.eventplanner.catalog;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class CatalogStatusController {
    private final JdbcTemplate db;
    private final boolean syncEnabled;
    private final long staleAfterMs;

    public CatalogStatusController(JdbcTemplate db,
            @Value("${linked-events.sync-enabled:true}") boolean syncEnabled,
            @Value("${linked-events.sync-delay-ms:3600000}") long syncDelayMs) {
        this.db = db;
        this.syncEnabled = syncEnabled;
        this.staleAfterMs = Math.max(60000, Math.multiplyExact(syncDelayMs, 2));
    }

    @GetMapping("/api/catalog/status")
    public Status status(@RequestParam(defaultValue="helsinki") String city) {
        var rows = db.query("""
                SELECT c.slug,r.id,r.status,r.started_at,r.finished_at,
                s.id AS success_id,s.finished_at AS success_at,
                (SELECT count(*) FROM events e WHERE e.city_id=c.id AND e.source=c.source AND e.is_active) AS cached_events
                FROM cities c
                LEFT JOIN LATERAL (SELECT * FROM sync_runs WHERE city_id=c.id
                  ORDER BY started_at DESC,id DESC LIMIT 1) r ON true
                LEFT JOIN LATERAL (SELECT id,finished_at FROM sync_runs WHERE city_id=c.id AND status='SUCCESS'
                  ORDER BY finished_at DESC,id DESC LIMIT 1) s ON true
                WHERE c.enabled AND c.slug=?
                """, (rs, index) -> {
            Instant success = instant(rs.getTimestamp("success_at"));
            long cached = rs.getLong("cached_events");
            boolean stale = (success == null && cached > 0)
                    || (success != null && success.isBefore(Instant.now().minusMillis(staleAfterMs)));
            return new Status(rs.getString("slug"), rs.getObject("id", Long.class),
                    rs.getString("status") == null ? "NOT_STARTED" : rs.getString("status"),
                    instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")),
                    rs.getObject("success_id", Long.class), success, cached, stale, syncEnabled);
        }, city);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестный город");
        return rows.get(0);
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record Status(String city, Long runId, String status, Instant startedAt, Instant finishedAt,
                         Long lastSuccessRunId, Instant lastSuccessAt, long cachedEvents,
                         boolean stale, boolean syncEnabled) { }
}
