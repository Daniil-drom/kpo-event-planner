package ru.kpo.eventplanner.visits;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VisitService {
    public enum Status { WANT_TO_VISIT, PLANNED, VISITED }

    public record Event(long id, String title, boolean isActive, String city, String timezone,
                        String place, String address, String siteUrl) {}
    public record Visit(UUID id, Event event, Status status, Instant startAt, Instant endAt,
                        boolean conflictAccepted, boolean hasConflict, Instant createdAt, Instant updatedAt) {}
    public record Conflict(UUID id, long eventId, String title, Instant startAt, Instant endAt) {}
    public record Changes(Status status, boolean startProvided, Instant startAt,
                          boolean endProvided, Instant endAt, boolean acceptConflict) {}

    public static class TimeConflict extends RuntimeException {
        private final List<Conflict> conflicts;
        public TimeConflict(List<Conflict> conflicts) {
            super("В это время уже запланированы другие мероприятия. Подтвердите сохранение с пересечением.");
            this.conflicts = List.copyOf(conflicts);
        }
        public List<Conflict> conflicts() { return conflicts; }
    }

    private static final String SELECT = """
        SELECT v.*, e.title, e.is_active, e.site_url, c.name AS city, c.timezone,
               p.title AS place, p.address,
               EXISTS (SELECT 1 FROM user_visits other
                   WHERE other.user_id=v.user_id AND other.id<>v.id
                     AND other.status='PLANNED' AND v.status='PLANNED'
                     AND other.start_at<v.end_at AND v.start_at<other.end_at) AS has_conflict
        FROM user_visits v JOIN events e ON e.id=v.event_id
        JOIN cities c ON c.id=e.city_id LEFT JOIN places p ON p.id=e.place_id
        WHERE v.user_id=?
        """;

    private final JdbcTemplate db;
    public VisitService(JdbcTemplate db) { this.db = db; }

    public List<Visit> list(UUID user) {
        return db.query(SELECT + " ORDER BY v.start_at NULLS LAST, v.created_at DESC, v.id", this::visit, user);
    }

    @Transactional
    public Visit create(UUID user, long event, Status status, Instant start, Instant end, boolean acceptConflict) {
        lockUser(user);
        validate(status, start, end);
        if (!db.queryForObject("SELECT EXISTS (SELECT 1 FROM events WHERE id=? AND is_active)", Boolean.class, event))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Мероприятие отсутствует в актуальном каталоге.");
        if (db.queryForObject("SELECT EXISTS (SELECT 1 FROM user_visits WHERE user_id=? AND event_id=?)", Boolean.class, user, event))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Мероприятие уже есть в вашем расписании. Измените существующую запись.");
        boolean confirmed = checkConflicts(user, null, status, start, end, acceptConflict);
        UUID id = db.queryForObject("""
            INSERT INTO user_visits(user_id,event_id,status,start_at,end_at,conflict_accepted)
            VALUES (?,?,?,?,?,?) RETURNING id
            """, UUID.class, user, event, status.name(), timestamp(start), timestamp(end), confirmed);
        return find(user, id);
    }

    @Transactional
    public Visit update(UUID user, UUID id, Changes changes) {
        lockUser(user);
        Visit old = find(user, id);
        Status status = changes.status() == null ? old.status() : changes.status();
        Instant start = changes.startProvided() ? changes.startAt() : old.startAt();
        Instant end = changes.endProvided() ? changes.endAt() : old.endAt();
        validate(status, start, end);
        boolean confirmed = checkConflicts(user, id, status, start, end, changes.acceptConflict());
        db.update("""
            UPDATE user_visits SET status=?,start_at=?,end_at=?,conflict_accepted=?,updated_at=now()
            WHERE id=? AND user_id=?
            """, status.name(), timestamp(start), timestamp(end), confirmed, id, user);
        return find(user, id);
    }

    @Transactional
    public void delete(UUID user, UUID id) {
        lockUser(user);
        if (db.update("DELETE FROM user_visits WHERE id=? AND user_id=?", id, user) == 0) throw notFound();
    }

    private Visit find(UUID user, UUID id) {
        var rows = db.query(SELECT + " AND v.id=?", this::visit, user, id);
        if (rows.isEmpty()) throw notFound();
        return rows.get(0);
    }

    private void lockUser(UUID user) {
        // Serialize changes to one user's plan, including concurrent requests from different tabs.
        // A second planned visit must see the first committed interval before checking overlaps.
        if (db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", UUID.class, user).isEmpty())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Войдите в аккаунт повторно.");
    }

    private void validate(Status status, Instant start, Instant end) {
        if (status == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Выберите статус посещения.");
        if ((start == null) != (end == null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите и начало, и окончание посещения.");
        if (status == Status.PLANNED && start == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для запланированного посещения укажите начало и окончание.");
        if (start != null && !end.isAfter(start))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Окончание посещения должно быть позже начала.");
    }

    private boolean checkConflicts(UUID user, UUID excluded, Status status, Instant start, Instant end, boolean accepted) {
        if (status != Status.PLANNED) return false;
        var conflicts = db.query("""
            SELECT v.id,v.event_id,e.title,v.start_at,v.end_at
            FROM user_visits v JOIN events e ON e.id=v.event_id
            WHERE v.user_id=? AND v.status='PLANNED' AND (CAST(? AS uuid) IS NULL OR v.id<>?)
              AND v.start_at<? AND ?<v.end_at ORDER BY v.start_at,v.id
            """, (rs, row) -> new Conflict(rs.getObject("id", UUID.class), rs.getLong("event_id"),
                rs.getString("title"), instant(rs, "start_at"), instant(rs, "end_at")),
                user, excluded, excluded, timestamp(end), timestamp(start));
        if (!conflicts.isEmpty() && !accepted) throw new TimeConflict(conflicts);
        return !conflicts.isEmpty() && accepted;
    }

    private Visit visit(ResultSet rs, int row) throws SQLException {
        var event = new Event(rs.getLong("event_id"), rs.getString("title"), rs.getBoolean("is_active"),
                rs.getString("city"), rs.getString("timezone"), rs.getString("place"), rs.getString("address"), rs.getString("site_url"));
        return new Visit(rs.getObject("id", UUID.class), event, Status.valueOf(rs.getString("status")),
                instant(rs, "start_at"), instant(rs, "end_at"), rs.getBoolean("conflict_accepted"),
                rs.getBoolean("has_conflict"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Посещение не найдено.");
    }
}
