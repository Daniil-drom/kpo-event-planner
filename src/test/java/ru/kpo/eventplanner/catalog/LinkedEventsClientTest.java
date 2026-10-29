package ru.kpo.eventplanner.catalog;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class LinkedEventsClientTest {
    HttpServer server;
    LinkedEventsClient client;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        client=new LinkedEventsClient("http://127.0.0.1:"+server.getAddress().getPort(),90,2);
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(200,bytes.length);
        exchange.getResponseBody().write(bytes); exchange.close();
    }
    @Test void readsAllPagesWithoutFollowingExternalNextUrlAndKeepsFixedWindow() {
        var calls=new AtomicInteger();
        var query=new AtomicReference<String>();
        server.createContext("/event/",exchange->{
            int n=calls.incrementAndGet();
            String current=exchange.getRequestURI().getQuery();
            assertThat(current).contains("page="+n,"division=helsinki","include=location,keywords","super_event_type=none");
            if (n==1) query.set(current); else assertThat(current.replace("page=2", "page=1")).isEqualTo(query.get());
            respond(exchange,n==1 ? "{\"meta\":{\"next\":\"https://untrusted.invalid/\"},\"data\":[{\"id\":\"helsinki:a\"}]}"
                    : "{\"meta\":{\"next\":null},\"data\":[{\"id\":\"helsinki:b\"}]}");
        });
        assertThat(client.events("helsinki")).extracting(n->n.path("id").asText()).containsExactly("helsinki:a","helsinki:b");
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void failsOnServerError() {
        server.createContext("/event/",exchange->{exchange.sendResponseHeaders(503,-1);exchange.close();});
        assertThatThrownBy(()->client.events("helsinki")).isInstanceOf(org.springframework.web.client.HttpServerErrorException.class);
    }
    @Test void rejectsMalformedPage() {
        server.createContext("/event/",exchange->respond(exchange,"{\"data\":[]}"));
        assertThatThrownBy(()->client.events("helsinki")).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsEmptyIntermediatePage() {
        server.createContext("/event/",exchange->respond(exchange,"{\"meta\":{\"next\":\"/event/?page=2\"},\"data\":[]}"));
        assertThatThrownBy(()->client.events("helsinki")).hasMessageContaining("Empty intermediate");
    }
    @Test void rejectsPaginationThatMakesNoProgress() {
        server.createContext("/event/",exchange->respond(exchange,"{\"meta\":{\"next\":\"/event/?page=2\"},\"data\":[{\"id\":\"helsinki:a\"}]}"));
        assertThatThrownBy(()->client.events("helsinki")).hasMessageContaining("no progress");
    }
    @Test void mergesOverlappingPagesByStringId() {
        var calls=new AtomicInteger();
        server.createContext("/event/",exchange->respond(exchange,calls.incrementAndGet()==1
                ? "{\"meta\":{\"next\":\"/event/?page=2\"},\"data\":[{\"id\":\"helsinki:a\"},{\"id\":\"helsinki:b\",\"value\":1}]}"
                : "{\"meta\":{\"next\":null},\"data\":[{\"id\":\"helsinki:b\",\"value\":2},{\"id\":\"helsinki:c\"}]}"));
        var events=client.events("helsinki");
        assertThat(events).extracting(e->e.path("id").asText()).containsExactly("helsinki:a","helsinki:b","helsinki:c");
        assertThat(events.get(1).path("value").asInt()).isEqualTo(2);
    }
    @Test void rejectsMissingEventId() {
        server.createContext("/event/",exchange->respond(exchange,"{\"meta\":{\"next\":null},\"data\":[{}]}"));
        assertThatThrownBy(()->client.events("helsinki")).hasMessageContaining("Missing event ID");
    }
    @Test void rejectsTruncatedPagination() {
        var calls=new AtomicInteger();
        server.createContext("/event/",exchange->respond(exchange,"{\"meta\":{\"next\":\"/event/?page=3\"},\"data\":[{\"id\":\"helsinki:"+calls.incrementAndGet()+"\"}]}"));
        assertThatThrownBy(()->client.events("helsinki")).hasMessageContaining("Pagination limit");
    }
}
