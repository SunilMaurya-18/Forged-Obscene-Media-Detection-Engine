package com.forgeddetect.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final Logger access = (Logger) LoggerFactory.getLogger("access");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        access.addAppender(appender);
    }

    @AfterEach
    void detach() {
        access.detachAppender(appender);
    }

    @Test
    void writesOneAccessLineWithTheRequestIdAndNoQueryString() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/appeals/N-1/status");
        request.setQueryString("pin=123456");
        request.addHeader(RequestIdFilter.HEADER, "edge-42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestIdFilter().doFilter(request, response, new MockFilterChain());

        assertThat(appender.list).hasSize(1);
        ILoggingEvent line = appender.list.get(0);
        assertThat(line.getMDCPropertyMap()).containsEntry(RequestIdFilter.MDC_KEY, "edge-42");
        assertThat(line.getFormattedMessage()).startsWith("GET /api/appeals/N-1/status 200 ").doesNotContain("pin");
        Map<String, Object> fields = line.getKeyValuePairs().stream().collect(Collectors.toMap(kv -> kv.key, kv -> kv.value));
        assertThat(fields).containsEntry("http_method", "GET").containsEntry("http_path", "/api/appeals/N-1/status")
                .containsEntry("http_status", 200).containsKey("duration_ms");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void actuatorRequestsAreNotLogged() throws Exception {
        new RequestIdFilter().doFilter(new MockHttpServletRequest("GET", "/actuator/health"),
                new MockHttpServletResponse(), new MockFilterChain());
        assertThat(appender.list).isEmpty();
    }
}
