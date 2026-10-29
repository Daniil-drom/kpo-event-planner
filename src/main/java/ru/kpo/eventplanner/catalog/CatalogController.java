package ru.kpo.eventplanner.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;

@RestController
@RequestMapping("/api")
public class CatalogController {
    private final JdbcTemplate db;
    public CatalogController(JdbcTemplate db) { this.db=db; }
    private static final String SELECT = """
        SELECT e.id,e.source,e.title,e.price,e.is_free AS "isFree",e.site_url AS "siteUrl",
        c.name AS city,c.timezone,p.title AS place,p.address,
        ns.start_at AS "nextSession",ns.has_start_time AS "nextSessionHasTime"
        FROM events e JOIN cities c ON c.id=e.city_id LEFT JOIN places p ON p.id=e.place_id
        LEFT JOIN LATERAL (
          SELECT s.start_at,s.has_start_time FROM event_sessions s WHERE s.event_id=e.id
          AND (s.end_at>=now() OR s.is_endless OR (s.end_at IS NULL AND s.start_at>=now()))
          ORDER BY s.start_at NULLS LAST LIMIT 1
        ) ns ON true
        """;
    @GetMapping("/categories")
    public List<Map<String,Object>> categories() { return db.queryForList("SELECT k.slug,k.name FROM categories k WHERE EXISTS (SELECT 1 FROM event_categories ec JOIN events e ON e.id=ec.event_id JOIN cities c ON c.id=e.city_id WHERE ec.category_id=k.id AND e.is_active AND c.enabled) ORDER BY k.name"); }

    @GetMapping("/events")
    public Map<String,Object> list(@RequestParam(defaultValue="helsinki") String city,
            @RequestParam(required=false) LocalDate dateFrom, @RequestParam(required=false) LocalDate dateTo,
            @RequestParam(required=false) String category, @RequestParam(required=false) Boolean free,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="12") int size) {
        if (page<0 || page>10000 || size<1 || size>100 || (dateFrom!=null && dateTo!=null && dateTo.isBefore(dateFrom)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Некорректные даты или номер страницы");
        var zones = db.queryForList("SELECT timezone FROM cities WHERE slug=? AND enabled", String.class, city);
        if (zones.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Неизвестный город");
        var args = new ArrayList<Object>(); args.add(city);
        var where = new StringBuilder(" WHERE c.slug=? AND c.enabled AND e.is_active");
        if (category!=null && !category.isBlank()) {
            where.append(" AND EXISTS (SELECT 1 FROM event_categories ec JOIN categories k ON k.id=ec.category_id WHERE ec.event_id=e.id AND k.slug=?)"); args.add(category);
        }
        if (free!=null) { where.append(" AND e.is_free=?"); args.add(free); }
        var zone=ZoneId.of(zones.get(0));
        if (dateFrom!=null || dateTo!=null) {
            where.append(" AND EXISTS (SELECT 1 FROM event_sessions s WHERE s.event_id=e.id");
            if(dateFrom!=null) {
                where.append(" AND (s.is_endless OR COALESCE(s.end_at,s.start_at)>=?)");
                args.add(Timestamp.from(dateFrom.atStartOfDay(zone).toInstant()));
            }
            if(dateTo!=null) {
                where.append(" AND (s.start_at IS NULL OR s.start_at<?)");
                args.add(Timestamp.from(dateTo.plusDays(1).atStartOfDay(zone).toInstant()));
            }
            where.append(")");
        } else {
            where.append(" AND (NOT EXISTS (SELECT 1 FROM event_sessions s WHERE s.event_id=e.id) OR EXISTS (SELECT 1 FROM event_sessions s WHERE s.event_id=e.id AND (s.is_endless OR COALESCE(s.end_at,s.start_at)>=now() OR (s.start_at IS NULL AND s.end_at IS NULL))))");
        }
        long total=db.queryForObject("SELECT count(*) FROM events e JOIN cities c ON c.id=e.city_id"+where,Long.class,args.toArray());
        args.add(size); args.add(page*size);
        var rows=db.queryForList(SELECT+where+" ORDER BY \"nextSession\" NULLS LAST,e.id LIMIT ? OFFSET ?",args.toArray());
        rows.forEach(CatalogController::dates);
        return Map.of("items",rows,"page",page,"size",size,"totalItems",total,"totalPages",(total+size-1)/size);
    }
    @GetMapping("/events/{id}")
    public Map<String,Object> detail(@PathVariable long id) {
        var rows=db.queryForList(SELECT+" WHERE e.id=?",id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Мероприятие не найдено");
        var result=rows.get(0); dates(result);
        result.putAll(db.queryForMap("SELECT is_active AS \"isActive\",description,age_restriction AS \"ageRestriction\" FROM events WHERE id=?",id));
        var sessions=db.queryForList("SELECT start_at AS \"startAt\",end_at AS \"endAt\",is_endless AS \"isEndless\",has_start_time AS \"hasStartTime\",has_end_time AS \"hasEndTime\" FROM event_sessions WHERE event_id=? ORDER BY start_at NULLS LAST",id);
        sessions.forEach(CatalogController::dates); result.put("sessions",sessions);
        result.put("categories",db.queryForList("SELECT c.slug,c.name FROM categories c JOIN event_categories ec ON ec.category_id=c.id WHERE ec.event_id=? ORDER BY c.name",id));
        return result;
    }
    private static void dates(Map<String,Object> row) {
        row.replaceAll((key,value)->value instanceof Timestamp t ? t.toInstant().toString() : value);
    }
}
