package ru.kpo.eventplanner.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;
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
        city=db.queryForObject("SELECT id FROM cities WHERE kudago_slug='ekb'",Long.class);
        cleanup();
    }
    @AfterEach void cleanup() { db.update("DELETE FROM events WHERE kudago_id IN (800000001,800000002)"); }
    JsonNode event(String title) throws Exception {
        var e=mapper.createObjectNode(); e.put("id",800000001);e.put("title",title);e.put("is_free",true);
        e.put("site_url","https://kudago.com/ekb/event/test");
        e.set("categories",mapper.readTree("[\"concert\"]"));
        // 00:00 on 2 January in Ekaterinburg; a point event with equal start/end.
        long t=java.time.Instant.parse("2030-01-01T19:00:00Z").getEpochSecond();
        e.set("dates",mapper.readTree("[{\"start\":"+t+",\"end\":"+t+"}]"));
        return e;
    }
    void save(JsonNode e) throws Exception { importer.save(city,mapper.readTree("[{\"slug\":\"concert\",\"name\":\"Концерты\"}]"),List.of(e)); }
    @Test void repeatedImportUpdatesWithoutDuplicatingSessions() throws Exception {
        save(event("First"));
        long id=db.queryForObject("SELECT id FROM events WHERE kudago_id=800000001",Long.class);
        save(event("Updated"));
        assertThat(db.queryForObject("SELECT id FROM events WHERE kudago_id=800000001",Long.class)).isEqualTo(id);
        assertThat(db.queryForObject("SELECT title FROM events WHERE id=?",String.class,id)).isEqualTo("Updated");
        assertThat(db.queryForObject("SELECT count(*) FROM event_sessions WHERE event_id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void invalidBatchRollsBackPreviousChanges() throws Exception {
        save(event("Original"));
        var bad=mapper.createObjectNode().put("id",800000002);
        assertThatThrownBy(()->importer.save(city,mapper.createArrayNode(),List.of(event("Changed"),bad)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT title FROM events WHERE kudago_id=800000001",String.class)).isEqualTo("Original");
    }
    @Test void filtersByCityCategoryPriceAndLocalDayAndOpensDetail() throws Exception {
        save(event("Concert"));
        String url="/api/events?city=ekb&category=concert&free=true&dateFrom=2030-01-02&dateTo=2030-01-02";
        var response=http.getForEntity(url,JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("totalItems").asInt()).isEqualTo(1);
        long id=response.getBody().path("items").get(0).path("id").asLong();
        assertThat(http.getForObject("/api/events/"+id,JsonNode.class).path("sessions").size()).isEqualTo(1);
        assertThat(http.getForObject(url.replace("free=true","free=false"),JsonNode.class).path("totalItems").asInt()).isZero();
        assertThat(http.getForObject(url.replace("city=ekb","city=msk"),JsonNode.class).path("totalItems").asInt()).isZero();
        assertThat(http.getForObject(url.replace("2030-01-02","2030-01-01"),JsonNode.class).path("totalItems").asInt()).isZero();
    }
    @Test void validatesRequestsAndMissingEvent() {
        for(var url:List.of("/api/events?size=101","/api/events?page=-1","/api/events?city=unknown","/api/events?dateFrom=2030-02-01&dateTo=2030-01-01"))
            assertThat(http.getForEntity(url,String.class).getStatusCode().value()).isEqualTo(400);
        assertThat(http.getForEntity("/api/events/9223372036854775807",String.class).getStatusCode().value()).isEqualTo(404);
    }
}
