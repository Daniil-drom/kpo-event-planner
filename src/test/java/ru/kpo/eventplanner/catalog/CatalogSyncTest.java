package ru.kpo.eventplanner.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CatalogSyncTest {
    @Test void failedDownloadPreservesCatalogueAndRecordsHttpStatus() {
        var db=mock(JdbcTemplate.class);
        var client=mock(LinkedEventsClient.class);
        var importer=mock(CatalogImport.class);
        when(db.queryForList("SELECT id,slug FROM cities WHERE enabled AND source='linked_events' ORDER BY id"))
                .thenReturn(List.of(Map.of("id",4L,"slug","helsinki")));
        when(db.queryForObject(anyString(),eq(Long.class),anyLong())).thenReturn(10L);
        when(client.events("helsinki")).thenThrow(HttpClientErrorException.create(
                HttpStatus.FORBIDDEN,"Forbidden",HttpHeaders.EMPTY,new byte[0],null));
        new CatalogSync(db,client,importer).synchronize();
        verifyNoInteractions(importer);
        verify(db).update("UPDATE sync_runs SET status='FAILED',finished_at=now(),error_message=? WHERE id=?","Forbidden: HTTP 403",10L);
    }
}
