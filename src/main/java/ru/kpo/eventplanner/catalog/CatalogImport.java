package ru.kpo.eventplanner.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.jsoup.Jsoup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class CatalogImport {
    private final JdbcTemplate db;
    public CatalogImport(JdbcTemplate db) { this.db = db; }

    @Transactional
    public void save(long city, List<JsonNode> events) {
        var zone = ZoneId.of(db.queryForObject("SELECT timezone FROM cities WHERE id=?", String.class, city));
        // A complete, valid snapshot replaces visibility, never internal IDs or personal plans.
        db.update("UPDATE events SET is_active=false WHERE city_id=? AND source='linked_events'", city);
        for (var e : events) {
            String externalId = required(e.path("id").asText(), "event ID");
            if (e.path("deleted").asBoolean() || !e.path("super_event_type").asText("").isBlank()
                    || (e.path("sub_events").isArray() && !e.path("sub_events").isEmpty())) continue;
            String status = e.path("event_status").asText();
            if (status.equals("EventCancelled") || status.equals("EventPostponed")) continue;
            if (!status.equals("EventScheduled") && !status.equals("EventRescheduled"))
                throw new IllegalArgumentException("Unknown event status: " + status);
            String title = required(text(e.path("name")), "event name");
            var start = timestamp(e.get("start_time"), zone, false);
            var end = timestamp(e.get("end_time"), zone, true);
            if (start == null || end != null && end.before(start))
                throw new IllegalArgumentException("Invalid event interval: " + externalId);

            Long placeId = null;
            var p = e.path("location");
            if (!p.isMissingNode() && !p.isNull()) {
                String placeExternalId = required(p.path("id").asText(), "expanded location ID");
                placeId = db.queryForObject("""
                    INSERT INTO places(source,external_id,city_id,title,address,site_url)
                    VALUES ('linked_events',?,?,?,?,?) ON CONFLICT(source,external_id) DO UPDATE SET
                    city_id=excluded.city_id,title=excluded.title,address=excluded.address,site_url=excluded.site_url
                    RETURNING id
                    """, Long.class, placeExternalId, city, required(text(p.path("name")), "place name"),
                        text(p.path("street_address")), safeUrl(text(p.path("info_url"))));
            }
            Boolean free = null;
            var prices = new LinkedHashSet<String>();
            var offers = e.path("offers");
            boolean allPaid = offers.isArray() && !offers.isEmpty();
            for (var offer : offers) {
                var flag = offer.path("is_free");
                if (flag.isBoolean() && flag.asBoolean()) free = true;
                if (!flag.isBoolean() || flag.asBoolean()) allPaid = false;
                String price = text(offer.path("price"));
                if (!price.isBlank()) prices.add(price);
            }
            if (free == null && allPaid) free = false;
            String url = safeUrl(text(e.path("info_url")));
            if (url == null) url = "https://tapahtumat.hel.fi/en/events/" +
                    org.springframework.web.util.UriUtils.encodePathSegment(externalId, java.nio.charset.StandardCharsets.UTF_8);
            String description = text(e.path("description"));
            if (description.isBlank()) description = text(e.path("short_description"));
            long id = db.queryForObject("""
                INSERT INTO events(source,external_id,city_id,place_id,title,description,price,is_free,
                    age_restriction,site_url,publication_date,is_active,source_status)
                VALUES ('linked_events',?,?,?,?,?,?,?,?,?,?,true,?)
                ON CONFLICT(source,external_id) DO UPDATE SET
                city_id=excluded.city_id,place_id=excluded.place_id,title=excluded.title,description=excluded.description,
                price=excluded.price,is_free=excluded.is_free,age_restriction=excluded.age_restriction,
                site_url=excluded.site_url,publication_date=excluded.publication_date,fetched_at=now(),
                is_active=true,source_status=excluded.source_status RETURNING id
                """, Long.class, externalId, city, placeId, title, description, String.join("; ", prices), free,
                    age(e), url, timestamp(e.get("date_published") == null || e.path("date_published").isNull()
                            ? e.get("created_time") : e.get("date_published")), status);
            db.update("DELETE FROM event_categories WHERE event_id=?", id);
            if (!e.path("keywords").isArray()) throw new IllegalArgumentException("Missing expanded keywords");
            for (var keyword : e.path("keywords")) {
                String slug = "linked-events:" + required(keyword.path("id").asText(), "expanded keyword ID");
                String name = required(text(keyword.path("name")), "keyword name");
                db.update("INSERT INTO categories(slug,name) VALUES (?,?) ON CONFLICT(slug) DO UPDATE SET name=excluded.name", slug, name);
                db.update("INSERT INTO event_categories SELECT ?,id FROM categories WHERE slug=? ON CONFLICT DO NOTHING", id, slug);
            }
            db.update("DELETE FROM event_sessions WHERE event_id=?", id);
            db.update("INSERT INTO event_sessions(event_id,start_at,end_at,has_start_time,has_end_time) VALUES (?,?,?,?,?)",
                    id, start, end, hasTime(e.get("start_time")), hasTime(e.get("end_time")));
        }
    }

    static String text(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return "";
        if (value.isTextual()) return Jsoup.parse(value.asText()).text();
        for (String language : List.of("ru", "en", "fi", "sv")) {
            String result = text(value.get(language));
            if (!result.isBlank()) return result;
        }
        var values = value.elements();
        while (values.hasNext()) {
            var item = values.next();
            if (item.isTextual() && !text(item).isBlank()) return text(item);
        }
        return "";
    }

    static String safeUrl(String url) {
        try {
            var uri = URI.create(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null ? uri.toString() : null;
        } catch (IllegalArgumentException e) { return null; }
    }

    private static String required(String value, String field) {
        if (value.isBlank()) throw new IllegalArgumentException("Missing " + field);
        return value;
    }

    private static String age(JsonNode event) {
        var min = event.path("audience_min_age");
        var max = event.path("audience_max_age");
        if (min.isNumber() && max.isNumber()) return min.asInt() + "–" + max.asInt();
        if (min.isNumber()) return min.asInt() + "+";
        return max.isNumber() ? "До " + max.asInt() : "";
    }

    static Timestamp timestamp(JsonNode value) {
        return timestamp(value, ZoneId.of("Europe/Helsinki"), false);
    }

    static boolean hasTime(JsonNode value) {
        return value != null && !value.isNull() && value.asText().contains("T");
    }

    static Timestamp timestamp(JsonNode value, ZoneId zone, boolean endOfDay) {
        if (value == null || value.isNull()) return null;
        String raw = value.asText();
        if (raw.matches("\\d{4}-\\d{2}-\\d{2}")) {
            var date = LocalDate.parse(raw);
            var instant = endOfDay ? date.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1)
                    : date.atStartOfDay(zone).toInstant();
            return Timestamp.from(instant);
        }
        return Timestamp.from(Instant.parse(raw));
    }
}
