package ru.kpo.eventplanner.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CatalogSyncTest {
    @Test void failedDownloadDoesNotCallImporterAndNextCityStillRuns() {
        var db=mock(JdbcTemplate.class);
        var client=mock(KudaGoClient.class);
        var importer=mock(CatalogImport.class);
        when(db.queryForList("SELECT id,kudago_slug FROM cities ORDER BY id"))
                .thenReturn(List.of(Map.of("id",1L,"kudago_slug","spb"),Map.of("id",2L,"kudago_slug","msk")));
        when(db.queryForObject(anyString(),eq(Long.class),anyLong())).thenReturn(10L,11L);
        when(client.categories()).thenThrow(new IllegalStateException("offline"));
        new CatalogSync(db,client,importer).synchronize();
        verify(client,times(2)).categories();
        verifyNoInteractions(importer);
        verify(db).update("UPDATE sync_runs SET status='FAILED',finished_at=now(),error_message=? WHERE id=?","IllegalStateException",10L);
        verify(db).update("UPDATE sync_runs SET status='FAILED',finished_at=now(),error_message=? WHERE id=?","IllegalStateException",11L);
    }
}
