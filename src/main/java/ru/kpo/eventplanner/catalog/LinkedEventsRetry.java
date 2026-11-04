package ru.kpo.eventplanner.catalog;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class LinkedEventsRetry {
    private final int maxAttempts;
    private final long delayMs, maxDelayMs, pageDelayMs;
    private final Clock clock;
    private final Sleeper sleeper;

    @FunctionalInterface
    interface Sleeper { void sleep(long millis) throws InterruptedException; }

    @Autowired
    public LinkedEventsRetry(
            @Value("${linked-events.max-attempts:3}") int maxAttempts,
            @Value("${linked-events.retry-delay-ms:1000}") long delayMs,
            @Value("${linked-events.max-retry-delay-ms:60000}") long maxDelayMs,
            @Value("${linked-events.page-delay-ms:250}") long pageDelayMs) {
        this(maxAttempts, delayMs, maxDelayMs, pageDelayMs, Clock.systemUTC(), Thread::sleep);
    }

    LinkedEventsRetry(int maxAttempts, long delayMs, long maxDelayMs, long pageDelayMs,
                      Clock clock, Sleeper sleeper) {
        if (maxAttempts < 1 || maxAttempts > 5 || delayMs < 1 || maxDelayMs < delayMs
                || maxDelayMs > 60000 || pageDelayMs < 0 || pageDelayMs > 60000)
            throw new IllegalArgumentException("Invalid Linked Events retry limits");
        this.maxAttempts = maxAttempts;
        this.delayMs = delayMs;
        this.maxDelayMs = maxDelayMs;
        this.pageDelayMs = pageDelayMs;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public <T> T execute(Supplier<T> request) {
        for (int attempt = 1; ; attempt++) {
            try {
                return request.get();
            } catch (RuntimeException error) {
                if (!retryable(error) || attempt >= maxAttempts) throw error;
                long delay = Math.max(Math.min(maxDelayMs, delayMs * (1L << (attempt - 1))), retryAfter(error));
                // Do not retry earlier than requested when the source asks for a long pause.
                if (delay > maxDelayMs) throw error;
                LoggerFactory.getLogger(getClass()).warn("Linked Events retry nextAttempt={} delay_ms={} reason={}",
                        attempt + 1, delay, error.getClass().getSimpleName());
                pause(delay);
            }
        }
    }

    public void betweenPages() { pause(pageDelayMs); }

    private boolean retryable(RuntimeException error) {
        if (error instanceof ResourceAccessException) return true;
        if (error instanceof RestClientResponseException response)
            return response.getStatusCode().value() == 429 || response.getStatusCode().is5xxServerError();
        return false;
    }

    private long retryAfter(RuntimeException error) {
        if (!(error instanceof RestClientResponseException response) || response.getResponseHeaders() == null) return 0;
        String value = response.getResponseHeaders().getFirst("Retry-After");
        if (value == null) return 0;
        value = value.trim();
        try {
            if (value.matches("[0-9]+")) {
                // Saturate instead of overflowing for an unexpectedly large header.
                if (value.length() > 6) return Long.MAX_VALUE;
                return Long.parseLong(value) * 1000;
            }
            return Math.max(0, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() - clock.millis());
        } catch (java.time.DateTimeException | NumberFormatException ignored) {
            return 0; // An invalid header falls back to the bounded exponential delay.
        }
    }

    private void pause(long millis) {
        if (millis == 0) return;
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Linked Events download interrupted", error);
        }
    }
}
