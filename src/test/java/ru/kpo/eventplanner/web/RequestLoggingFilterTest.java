package ru.kpo.eventplanner.web;

import java.util.List;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestLoggingFilterTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLogs() {
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void logsStatusAndDurationWithoutCredentials() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setQueryString("password=secret-query");
        request.addHeader("Cookie", "JSESSIONID=secret-cookie");
        request.setContent("secret-body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();

        new RequestLoggingFilter().doFilter(request, response,
                (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(401));

        assertThat(messages()).singleElement().asString()
                .contains("method=POST", "path=/api/auth/login", "status=401", "duration_ms=")
                .doesNotContain("secret-query", "secret-cookie", "secret-body");
    }

    @Test
    void logsFailureAndPropagatesException() {
        var request = new MockHttpServletRequest("GET", "/api/cities");
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> new RequestLoggingFilter().doFilter(request, response,
                (req, res) -> { throw new ServletException("failure"); }))
                .isInstanceOf(ServletException.class);
        assertThat(messages()).singleElement().asString().contains("status=500", "duration_ms=");
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
