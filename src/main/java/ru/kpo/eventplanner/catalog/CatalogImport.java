package ru.kpo.eventplanner.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Service
public class CatalogImport {
    private final JdbcTemplate db;
    public CatalogImport(JdbcTemplate db) { this.db = db; }

    @Transactional
    public void save(long city, JsonNode categories, List<JsonNode> events) {
        for (var c : categories) db.update("INSERT INTO categories(slug,name) VALUES (?,?) ON CONFLICT(slug) DO UPDATE SET name=excluded.name",
                c.path("slug").asText(), c.path("name").asText());
        for (var e : events) {
            if (e.path("id").asLong() <= 0 || e.path("title").asText().isBlank() || e.path("site_url").asText().isBlank())
                throw new IllegalArgumentException("Event without id, title or source URL");
            Long placeId = null;
            var p = e.path("place");
            if (p.path("id").asLong() > 0) placeId = db.queryForObject("""
                INSERT INTO places(kudago_id,city_id,title,address,site_url) VALUES (?,?,?,?,?)
                ON CONFLICT(kudago_id) DO UPDATE SET title=excluded.title,address=excluded.address,site_url=excluded.site_url
                RETURNING id
                """, Long.class, p.path("id").asLong(), city, p.path("title").asText(), p.path("address").asText(), p.path("site_url").asText());
            long id = db.queryForObject("""
                INSERT INTO events(kudago_id,city_id,place_id,title,description,price,is_free,age_restriction,site_url,publication_date)
                VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(kudago_id) DO UPDATE SET
                city_id=excluded.city_id,place_id=excluded.place_id,title=excluded.title,description=excluded.description,
                price=excluded.price,is_free=excluded.is_free,age_restriction=excluded.age_restriction,
                site_url=excluded.site_url,publication_date=excluded.publication_date,fetched_at=now() RETURNING id
                """, Long.class, e.path("id").asLong(), city, placeId, e.path("title").asText(), e.path("description").asText(),
                    e.path("price").asText(), e.path("is_free").asBoolean(), e.path("age_restriction").asText(),
                    e.path("site_url").asText(), timestamp(e.get("publication_date")));
            db.update("DELETE FROM event_categories WHERE event_id=?", id);
            for (var c : e.path("categories")) {
                db.update("INSERT INTO categories(slug,name) VALUES (?,?) ON CONFLICT DO NOTHING", c.asText(), c.asText());
                db.update("INSERT INTO event_categories SELECT ?,id FROM categories WHERE slug=? ON CONFLICT DO NOTHING", id, c.asText());
            }
            db.update("DELETE FROM event_sessions WHERE event_id=?", id);
            for (var d : e.path("dates")) db.update("""
                INSERT INTO event_sessions(event_id,start_at,end_at,is_continuous,is_endless) VALUES (?,?,?,?,?)
                """, id, d.path("is_startless").asBoolean() ? null : timestamp(d.get("start")),
                    d.path("is_endless").asBoolean() ? null : timestamp(d.get("end")),
                    d.path("is_continuous").asBoolean(), d.path("is_endless").asBoolean());
        }
    }
    static Timestamp timestamp(JsonNode value) {
        return value == null || value.isNull() ? null : Timestamp.from(Instant.ofEpochSecond(value.asLong()));
    }
}
