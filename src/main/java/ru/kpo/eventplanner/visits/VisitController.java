package ru.kpo.eventplanner.visits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ru.kpo.eventplanner.auth.AccountPrincipal;

@RestController
@RequestMapping("/api/visits")
public class VisitController {
    private final VisitService visits;
    public VisitController(VisitService visits) { this.visits = visits; }

    @GetMapping
    public ResponseEntity<List<VisitService.Visit>> list(@AuthenticationPrincipal AccountPrincipal user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(visits.list(user.getId()));
    }

    @PostMapping
    public ResponseEntity<VisitService.Visit> create(@AuthenticationPrincipal AccountPrincipal user,
                                                   @Valid @RequestBody NewVisit input) {
        var visit = visits.create(user.getId(), input.eventId(), input.status(), instant(input.startAt()),
                instant(input.endAt()), input.acceptConflict());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(visit);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<VisitService.Visit> update(@AuthenticationPrincipal AccountPrincipal user,
                                                   @PathVariable UUID id, @RequestBody ObjectNode input) {
        if (input == null || input.isEmpty()) throw badRequest("Укажите изменения посещения.");
        Set<String> allowed = Set.of("status", "startAt", "endAt", "acceptConflict");
        input.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw badRequest("Можно изменить только статус и время посещения.");
        });
        VisitService.Status status = null;
        if (input.has("status")) {
            try { status = VisitService.Status.valueOf(input.path("status").asText()); }
            catch (IllegalArgumentException error) { throw badRequest("Неизвестный статус посещения."); }
        }
        boolean accepted = false;
        if (input.has("acceptConflict")) {
            if (!input.path("acceptConflict").isBoolean()) throw badRequest("Подтверждение пересечения должно быть true или false.");
            accepted = input.path("acceptConflict").asBoolean();
        }
        var changes = new VisitService.Changes(status, input.has("startAt"), parseTime(input.get("startAt")),
                input.has("endAt"), parseTime(input.get("endAt")), accepted);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(visits.update(user.getId(), id, changes));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AccountPrincipal user, @PathVariable UUID id) {
        visits.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    public record NewVisit(@NotNull(message="Укажите мероприятие.") @Positive(message="Некорректное мероприятие.") Long eventId,
                           @NotNull(message="Выберите статус посещения.") VisitService.Status status,
                           OffsetDateTime startAt, OffsetDateTime endAt, boolean acceptConflict) {}

    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
    private static Instant parseTime(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isTextual()) {
            try { return OffsetDateTime.parse(value.asText()).toInstant(); }
            catch (DateTimeParseException ignored) { /* Return the same safe validation message below. */ }
        }
        throw badRequest("Передавайте время в ISO 8601 с часовым поясом, например 2026-11-10T18:00:00+03:00.");
    }
    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
