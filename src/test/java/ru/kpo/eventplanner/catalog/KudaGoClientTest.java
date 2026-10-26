package ru.kpo.eventplanner.catalog;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class KudaGoClientTest {
    HttpServer server;
    KudaGoClient client;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        client=new KudaGoClient("http://127.0.0.1:"+server.getAddress().getPort());
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void readsAllPagesWithoutFollowingExternalNextUrl() {
        var calls=new AtomicInteger();
        server.createContext("/events/",exchange->{
            int n=calls.incrementAndGet();
            assertThat(exchange.getRequestURI().getQuery()).contains("page="+n,"location=spb","text_format=text");
            byte[] bytes=(n==1 ? "{\"next\":\"https://untrusted.invalid/\",\"results\":[{\"id\":1}]}" : "{\"next\":null,\"results\":[{\"id\":2}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        assertThat(client.events("spb")).extracting(n->n.path("id").asInt()).containsExactly(1,2);
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void failsOnServerError() {
        server.createContext("/events/",exchange->{exchange.sendResponseHeaders(503,-1);exchange.close();});
        assertThatThrownBy(()->client.events("spb")).isInstanceOf(RuntimeException.class);
    }
    @Test void rejectsMalformedPage() {
        server.createContext("/events/",exchange->{
            byte[] bytes="{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        assertThatThrownBy(()->client.events("spb")).isInstanceOf(IllegalStateException.class);
    }
}
