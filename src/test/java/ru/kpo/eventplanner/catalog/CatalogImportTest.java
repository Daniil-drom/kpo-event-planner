package ru.kpo.eventplanner.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CatalogImportTest {
    ObjectMapper mapper = new ObjectMapper();
    @Test void selectsAvailableLanguageAndConvertsHtmlToPlainText() throws Exception {
        assertThat(CatalogImport.text(mapper.readTree("{\"ru\":\" \",\"en\":\"<p>Art &amp; music</p><script>alert(1)</script>\",\"fi\":\"Taide\"}")))
                .isEqualTo("Art & music");
        assertThat(CatalogImport.text(mapper.readTree("{\"fi\":\"Kirjasto\"}"))).isEqualTo("Kirjasto");
        assertThat(CatalogImport.text(mapper.readTree("{\"ru\":\"Концерт\",\"en\":\"Concert\"}"))).isEqualTo("Концерт");
    }
    @Test void parsesIsoTimeAndLeavesMissingEndUnknown() throws Exception {
        assertThat(CatalogImport.timestamp(mapper.readTree("\"2030-01-01T22:00:00Z\"")).toInstant().toString()).isEqualTo("2030-01-01T22:00:00Z");
        assertThat(CatalogImport.timestamp(mapper.nullNode())).isNull();
        assertThatThrownBy(()->CatalogImport.timestamp(mapper.readTree("\"invalid\""))).isInstanceOf(java.time.format.DateTimeParseException.class);
    }
    @Test void dateOnlyUsesHelsinkiCalendarDayIncludingDstTransition() throws Exception {
        var value=mapper.readTree("\"2030-03-31\"");
        var zone=java.time.ZoneId.of("Europe/Helsinki");
        var start=CatalogImport.timestamp(value,zone,false).toInstant();
        var end=CatalogImport.timestamp(value,zone,true).toInstant();
        assertThat(start.toString()).isEqualTo("2030-03-30T22:00:00Z");
        assertThat(end.toString()).isEqualTo("2030-03-31T20:59:59.999Z");
        assertThat(java.time.Duration.between(start,end.plusMillis(1)).toHours()).isEqualTo(23);
        assertThat(CatalogImport.hasTime(value)).isFalse();
    }
    @Test void onlyAllowsHttpSourceLinks() {
        assertThat(CatalogImport.safeUrl("javascript:alert(1)")).isNull();
        assertThat(CatalogImport.safeUrl("https://user:pass@example.com/")).isNull();
        assertThat(CatalogImport.safeUrl("https://organizer.example/event?a=1&b=2")).isEqualTo("https://organizer.example/event?a=1&b=2");
    }
}
