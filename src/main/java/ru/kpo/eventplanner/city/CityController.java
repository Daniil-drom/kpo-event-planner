package ru.kpo.eventplanner.city;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cities")
public class CityController {
    private final JdbcTemplate jdbc;

    public CityController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<CityResponse> findAll() {
        return jdbc.query("""
                SELECT id, kudago_slug, name, timezone
                FROM cities ORDER BY name
                """, (row, number) -> new CityResponse(
                row.getLong("id"), row.getString("kudago_slug"),
                row.getString("name"), row.getString("timezone")));
    }

    public record CityResponse(long id, String slug, String name, String timezone) {
    }
}
