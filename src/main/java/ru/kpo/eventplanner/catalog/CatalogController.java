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
        SELECT e.id,e.title,e.price,e.is_free AS "isFree",e.site_url AS "siteUrl",
        c.name AS city,c.timezone,p.title AS place,p.address,
        (SELECT min(s.start_at) FROM event_sessions s WHERE s.event_id=e.id
          AND (s.end_at>=now() OR s.is_endless OR (s.end_at IS NULL AND s.start_at>=now()))) AS "nextSession"
        FROM events e JOIN cities c ON c.id=e.city_id LEFT JOIN places p ON p.id=e.place_id
        """;
    @GetMapping("/categories")
    public List<Map<String,Object>> categories() { return db.queryForList("SELECT slug,name FROM categories ORDER BY name"); }

    @GetMapping("/events")
    public Map<String,Object> list(@RequestParam(defaultValue="spb") String city,
            @RequestParam(required=false) LocalDate dateFrom, @RequestParam(required=false) LocalDate dateTo,
            @RequestParam(required=false) String category, @RequestParam(required=false) Boolean free,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="12") int size) {
        if (page<0 || page>10000 || size<1 || size>100 || (dateFrom!=null && dateTo!=null && dateTo.isBefore(dateFrom)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Некорректные даты или номер страницы");
        var zones = db.queryForList("SELECT timezone FROM cities WHERE kudago_slug=?", String.class, city);
        if (zones.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Неизвестный город");
        var args = new ArrayList<Object>(); args.add(city);
        var where = new StringBuilder(" WHERE c.kudago_slug=?");
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
        result.putAll(db.queryForMap("SELECT description,age_restriction AS \"ageRestriction\" FROM events WHERE id=?",id));
        var sessions=db.queryForList("SELECT start_at AS \"startAt\",end_at AS \"endAt\",is_endless AS \"isEndless\" FROM event_sessions WHERE event_id=? ORDER BY start_at NULLS LAST",id);
        sessions.forEach(CatalogController::dates); result.put("sessions",sessions);
        result.put("categories",db.queryForList("SELECT c.slug,c.name FROM categories c JOIN event_categories ec ON ec.category_id=c.id WHERE ec.event_id=? ORDER BY c.name",id));
        return result;
    }
    private static void dates(Map<String,Object> row) {
        row.replaceAll((key,value)->value instanceof Timestamp t ? t.toInstant().toString() : value);
    }
}
