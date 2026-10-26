package ru.kpo.eventplanner.catalog;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="kudago.sync-enabled", havingValue="true", matchIfMissing=true)
public class CatalogSync {
    private final JdbcTemplate db;
    private final KudaGoClient client;
    private final CatalogImport importer;
    public CatalogSync(JdbcTemplate db, KudaGoClient client, CatalogImport importer) {
        this.db=db; this.client=client; this.importer=importer;
    }
    @Scheduled(initialDelay=5000, fixedDelayString="${kudago.sync-delay-ms:3600000}")
    public void synchronize() {
        var cities = db.queryForList("SELECT id,kudago_slug FROM cities ORDER BY id");
        for (var city : cities) {
            long id = ((Number)city.get("id")).longValue();
            long run = db.queryForObject("INSERT INTO sync_runs(city_id,status) VALUES (?,'RUNNING') RETURNING id", Long.class, id);
            try {
                var categories = client.categories();
                var events = client.events((String)city.get("kudago_slug"));
                importer.save(id, categories, events);
                db.update("UPDATE sync_runs SET status='SUCCESS',finished_at=now() WHERE id=?", run);
                LoggerFactory.getLogger(getClass()).info("Catalogue synced city={} events={}", city.get("kudago_slug"), events.size());
            } catch (RuntimeException e) {
                db.update("UPDATE sync_runs SET status='FAILED',finished_at=now(),error_message=? WHERE id=?", e.getClass().getSimpleName(), run);
                LoggerFactory.getLogger(getClass()).warn("Catalogue sync failed city={} reason={}", city.get("kudago_slug"), e.getClass().getSimpleName());
            }
        }
    }
}
