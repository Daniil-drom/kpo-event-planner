package ru.kpo.eventplanner.visits;

import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes=VisitController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class VisitErrors {
    @ExceptionHandler(VisitService.TimeConflict.class)
    public ResponseEntity<?> conflict(VisitService.TimeConflict error) {
        return ResponseEntity.status(409).body(Map.of("code", "TIME_CONFLICT", "message", error.getMessage(), "conflicts", error.conflicts()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getDefaultMessage()).findFirst().orElse("Проверьте данные посещения.");
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> unreadable() {
        return ResponseEntity.badRequest().body(Map.of("message", "Проверьте статус, идентификатор и даты. Время должно содержать часовой пояс."));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> rejected(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", error.getReason()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<?> duplicate() {
        return ResponseEntity.status(409).body(Map.of("message", "Мероприятие уже есть в вашем расписании."));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).body(Map.of("message", "Расписание временно недоступно. Повторите действие позже."));
    }
}
