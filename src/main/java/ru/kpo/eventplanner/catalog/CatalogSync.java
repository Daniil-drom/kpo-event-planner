package ru.kpo.eventplanner.catalog;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="linked-events.sync-enabled", havingValue="true", matchIfMissing=true)
public class CatalogSync {
    private final JdbcTemplate db;
    private final LinkedEventsClient client;
    private final CatalogImport importer;
    public CatalogSync(JdbcTemplate db, LinkedEventsClient client, CatalogImport importer) {
        this.db=db; this.client=client; this.importer=importer;
    }
    @Scheduled(initialDelay=5000, fixedDelayString="${linked-events.sync-delay-ms:3600000}")
    public void synchronize() {
        var cities = db.queryForList("SELECT id,slug FROM cities WHERE enabled AND source='linked_events' ORDER BY id");
        for (var city : cities) {
            long id = ((Number)city.get("id")).longValue();
            long run = db.queryForObject("INSERT INTO sync_runs(city_id,status) VALUES (?,'RUNNING') RETURNING id", Long.class, id);
            try {
                var events = client.events((String)city.get("slug"));
                importer.save(id, events);
                db.update("UPDATE sync_runs SET status='SUCCESS',finished_at=now() WHERE id=?", run);
                LoggerFactory.getLogger(getClass()).info("Catalogue synced city={} events={}", city.get("slug"), events.size());
            } catch (RuntimeException e) {
                String reason = e.getClass().getSimpleName();
                if (e instanceof org.springframework.web.client.RestClientResponseException response)
                    reason += ": HTTP " + response.getStatusCode().value();
                else if (e instanceof IllegalArgumentException || e instanceof IllegalStateException)
                    reason += ": " + String.valueOf(e.getMessage()).replaceAll("[\\r\\n]", " ");
                if (reason.length() > 500) reason = reason.substring(0, 500);
                db.update("UPDATE sync_runs SET status='FAILED',finished_at=now(),error_message=? WHERE id=?", reason, run);
                LoggerFactory.getLogger(getClass()).warn("Catalogue sync failed city={} reason={}", city.get("slug"), reason);
            }
        }
    }
}
