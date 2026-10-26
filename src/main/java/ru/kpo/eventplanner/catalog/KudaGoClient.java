package ru.kpo.eventplanner.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class KudaGoClient {
    private final RestClient http;
    public KudaGoClient(@Value("${kudago.base-url:https://kudago.com/public-api/v1.4}") String url) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    public JsonNode categories() {
        var data = http.get().uri("/event-categories/?lang=ru").retrieve().body(JsonNode.class);
        if (data == null || !data.isArray()) throw new IllegalStateException("Invalid categories response");
        return data;
    }
    public List<JsonNode> events(String city) {
        var events = new ArrayList<JsonNode>();
        long since = Instant.now().getEpochSecond();
        for (int page = 1; page <= 100; page++) {
            final int number = page;
            var data = http.get().uri(b -> b.path("/events/")
                    .queryParam("location", city).queryParam("page", number).queryParam("page_size", 100)
                    .queryParam("actual_since", since).queryParam("order_by", "id")
                    .queryParam("text_format", "text").queryParam("expand", "place,dates")
                    .queryParam("fields", "id,title,description,price,is_free,age_restriction,site_url,publication_date,place,dates,categories")
                    .build()).retrieve().body(JsonNode.class);
            if (data == null || !data.path("results").isArray() || !data.has("next"))
                throw new IllegalStateException("Invalid events response");
            data.path("results").forEach(events::add);
            if (data.path("next").isNull()) return events;
            if (data.path("results").isEmpty()) throw new IllegalStateException("Empty intermediate page");
        }
        throw new IllegalStateException("Pagination limit exceeded; previous catalogue preserved");
    }
}
