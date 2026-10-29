package ru.kpo.eventplanner.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Component
public class LinkedEventsClient {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LinkedEventsClient.class);
    private final RestClient http;
    private final int horizonDays;
    private final int maxPages;

    public LinkedEventsClient(
            @Value("${linked-events.base-url:https://api.hel.fi/linkedevents/v1}") String url,
            @Value("${linked-events.horizon-days:90}") int horizonDays,
            @Value("${linked-events.max-pages:200}") int maxPages) {
        if (horizonDays < 1 || maxPages < 1) throw new IllegalArgumentException("Invalid import limits");
        this.horizonDays = horizonDays;
        this.maxPages = maxPages;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(60000);
        http = RestClient.builder().baseUrl(url).requestFactory(factory)
                .defaultHeader("User-Agent", "EventPlanner/0.1")
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE).build();
    }

    public List<JsonNode> events(String division) {
        var events = new LinkedHashMap<String, JsonNode>();
        // Fix the time window for all pages of this snapshot.
        var start = Instant.now();
        var end = start.plus(horizonDays, ChronoUnit.DAYS);
        for (int page = 1; page <= maxPages; page++) {
            final int number = page;
            var data = http.get().uri(b -> b.path("/event/")
                    .queryParam("format", "json").queryParam("division", division)
                    .queryParam("page", number).queryParam("page_size", 100)
                    .queryParam("start", start.toString()).queryParam("end", end.toString())
                    .queryParam("sort", "last_modified_time,start_time,name").queryParam("super_event_type", "none")
                    .queryParam("include", "location,keywords")
                    .build()).retrieve().body(JsonNode.class);
            if (data == null || !data.path("data").isArray() || !data.path("meta").has("next"))
                throw new IllegalStateException("Invalid Linked Events page " + page);
            var next = data.path("meta").get("next");
            if (!next.isNull() && (!next.isTextual() || next.asText().isBlank()))
                throw new IllegalStateException("Invalid pagination metadata");
            int previousSize = events.size();
            for (var event : data.path("data")) {
                String id = event.path("id").asText();
                if (id.isBlank()) throw new IllegalStateException("Missing event ID on page " + page);
                // Offset pages can overlap while the source is changing; keep the latest copy.
                events.put(id, event);
            }
            if (page == 1 || page % 10 == 0 || next.isNull())
                log.info("Linked Events download city={} page={} uniqueEvents={}", division, page, events.size());
            if (next.isNull()) return new ArrayList<>(events.values());
            if (data.path("data").isEmpty()) throw new IllegalStateException("Empty intermediate page");
            if (events.size() == previousSize) throw new IllegalStateException("Pagination made no progress");
            // Never follow an upstream URL: construct the next request against our configured host.
        }
        throw new IllegalStateException("Pagination limit exceeded; previous catalogue preserved");
    }
}
