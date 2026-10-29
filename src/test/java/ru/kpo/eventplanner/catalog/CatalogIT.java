package ru.kpo.eventplanner.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CatalogIT {
    @Autowired JdbcTemplate db;
    @Autowired CatalogImport importer;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    long city;
    @BeforeEach void prepare() {
        city=db.queryForObject("SELECT id FROM cities WHERE slug='helsinki'",Long.class);
        cleanup();
    }
    @AfterEach void cleanup() {
        db.update("DELETE FROM users WHERE email='helsinki-test@example.test'");
        db.update("DELETE FROM events WHERE source='linked_events' AND external_id LIKE 'test:%'");
        db.update("DELETE FROM places WHERE source='linked_events' AND external_id='test:place'");
        db.update("DELETE FROM categories WHERE slug='linked-events:test:concert'");
    }
    ObjectNode event(String title) throws Exception {
        var e=(ObjectNode)mapper.readTree("""
            {"id":"test:event", "name":{"fi":"Konsertti"}, "event_status":"EventScheduled",
             "description":{"en":"<p>Live &amp; music</p><script>alert(1)</script>"},
             "info_url":{"en":"https://example.test/event"},
             "location":{"id":"test:place","name":{"en":"Library"},"street_address":{"fi":"Testikatu 1"}},
             "keywords":[{"id":"test:concert","name":{"en":"Concert"}}],
             "offers":[{"is_free":true}],"start_time":"2030-01-01T22:00:00Z","end_time":"2030-01-01T23:00:00Z"}
            """);
        ((ObjectNode)e.get("name")).put("en",title);
        return e;
    }
    void save(JsonNode e) { importer.save(city,List.of(e)); }
    long eventId() { return db.queryForObject("SELECT id FROM events WHERE source='linked_events' AND external_id='test:event'",Long.class); }
    @Test void repeatedImportUpdatesWithoutDuplicatingPlacesCategoriesOrSessions() throws Exception {
        save(event("First"));
        long id=eventId();
        save(event("Updated"));
        assertThat(eventId()).isEqualTo(id);
        assertThat(db.queryForObject("SELECT title FROM events WHERE id=?",String.class,id)).isEqualTo("Updated");
        assertThat(db.queryForObject("SELECT description FROM events WHERE id=?",String.class,id)).isEqualTo("Live & music");
        assertThat(db.queryForObject("SELECT count(*) FROM event_sessions WHERE event_id=?",Long.class,id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM places WHERE source='linked_events' AND external_id='test:place'",Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM event_categories WHERE event_id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void invalidBatchRollsBackUpdatesAndVisibility() throws Exception {
        save(event("Original"));
        var bad=mapper.createObjectNode().put("id","test:invalid").put("event_status","EventScheduled");
        assertThatThrownBy(()->importer.save(city,List.of(event("Changed"),bad))).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT title FROM events WHERE id=?",String.class,eventId())).isEqualTo("Original");
        assertThat(db.queryForObject("SELECT is_active FROM events WHERE id=?",Boolean.class,eventId())).isTrue();
    }
    @Test void filtersByCategoryPriceAndHelsinkiDayAndOpensDetail() throws Exception {
        save(event("Concert"));
        String url="/api/events?city=helsinki&category=linked-events:test:concert&free=true&dateFrom=2030-01-02&dateTo=2030-01-02";
        var response=http.getForEntity(url,JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("totalItems").asInt()).isEqualTo(1);
        long id=response.getBody().path("items").get(0).path("id").asLong();
        var detail=http.getForObject("/api/events/"+id,JsonNode.class);
        assertThat(detail.path("sessions").size()).isEqualTo(1);
        assertThat(detail.path("timezone").asText()).isEqualTo("Europe/Helsinki");
        assertThat(detail.path("isActive").asBoolean()).isTrue();
        assertThat(http.getForObject(url.replace("free=true","free=false"),JsonNode.class).path("totalItems").asInt()).isZero();
        assertThat(http.getForObject(url.replace("2030-01-02","2030-01-01"),JsonNode.class).path("totalItems").asInt()).isZero();
        assertThat(http.getForObject("/api/categories",JsonNode.class).toString()).contains("linked-events:test:concert");
    }
    @Test void cancelledOrMissingEventsDisappearButKeepPersonalPlansAndInternalId() throws Exception {
        save(event("Original"));
        long id=eventId();
        UUID user=db.queryForObject("INSERT INTO users(email,password_hash) VALUES ('helsinki-test@example.test','test') RETURNING id",UUID.class);
        db.update("INSERT INTO user_visits(user_id,event_id,status) VALUES (?,?,'WANT_TO_VISIT')",user,id);
        save(event("Cancelled").put("event_status","EventCancelled"));
        assertThat(db.queryForObject("SELECT is_active FROM events WHERE id=?",Boolean.class,id)).isFalse();
        assertThat(db.queryForObject("SELECT event_id FROM user_visits WHERE user_id=?",Long.class,user)).isEqualTo(id);
        assertThat(http.getForObject("/api/events?category=linked-events:test:concert",JsonNode.class).path("totalItems").asInt()).isZero();
        save(event("Restored"));
        assertThat(eventId()).isEqualTo(id);
        importer.save(city,List.of());
        assertThat(db.queryForObject("SELECT is_active FROM events WHERE id=?",Boolean.class,id)).isFalse();
        assertThat(http.getForObject("/api/categories",JsonNode.class).toString()).doesNotContain("linked-events:test:concert");
    }
    @Test void doesNotInventPriceOrEndTimeAndSkipsRecurringParents() throws Exception {
        var event=event("Unknown price");event.set("offers",mapper.createArrayNode());event.putNull("end_time");event.putNull("info_url");
        save(event);
        assertThat(db.queryForObject("SELECT is_free FROM events WHERE id=?",Boolean.class,eventId())).isNull();
        assertThat(db.queryForObject("SELECT end_at FROM event_sessions WHERE event_id=?",java.sql.Timestamp.class,eventId())).isNull();
        assertThat(db.queryForObject("SELECT site_url FROM events WHERE id=?",String.class,eventId())).isEqualTo("https://tapahtumat.hel.fi/en/events/test:event");
        save(event.put("super_event_type","recurring"));
        assertThat(db.queryForObject("SELECT is_active FROM events WHERE id=?",Boolean.class,eventId())).isFalse();
    }
    @Test void importsDateOnlyAndDoesNotLeakIntoFollowingDay() throws Exception {
        save(event("Exhibition").put("start_time","2030-03-31").put("end_time","2030-03-31"));
        var detail=http.getForObject("/api/events/"+eventId(),JsonNode.class);
        assertThat(detail.path("sessions").get(0).path("hasStartTime").asBoolean()).isFalse();
        assertThat(detail.path("sessions").get(0).path("hasEndTime").asBoolean()).isFalse();
        String url="/api/events?category=linked-events:test:concert&dateFrom=2030-03-31&dateTo=2030-03-31";
        var list=http.getForObject(url,JsonNode.class);
        assertThat(list.path("totalItems").asInt()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("nextSessionHasTime").asBoolean()).isFalse();
        assertThat(http.getForObject(url.replace("2030-03-31","2030-04-01"),JsonNode.class).path("totalItems").asInt()).isZero();
    }
    @Test void validatesRequestsAndMissingEvent() {
        for(var url:List.of("/api/events?size=101","/api/events?page=-1","/api/events?city=unknown","/api/events?city=spb","/api/events?dateFrom=2030-02-01&dateTo=2030-01-01"))
            assertThat(http.getForEntity(url,String.class).getStatusCode().value()).isEqualTo(400);
        assertThat(http.getForEntity("/api/events/9223372036854775807",String.class).getStatusCode().value()).isEqualTo(404);
    }
}
