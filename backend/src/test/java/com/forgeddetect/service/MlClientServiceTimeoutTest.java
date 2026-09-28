package com.forgeddetect.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.forgeddetect.observability.FmdMetrics;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class MlClientServiceTimeoutTest {

    @Test
    void aStalledMlServiceFailsTheCallAfterTheTimeoutAndIsCountedAsATimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer ml = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ml.createContext("/infer", ex -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        ml.start();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MlClientService client = new MlClientService(WebClient.builder(),
                "http://127.0.0.1:" + ml.getAddress().getPort(), Duration.ofMillis(300),
                new FmdMetrics(registry, mock(AnalysisResultRepository.class)));
        try {
            long started = System.nanoTime();
            assertThatThrownBy(() -> client.infer(new byte[] {1, 2, 3}, "x.jpg", "image/jpeg"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("/infer");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            assertThat(registry.get("fmd.ml.client.errors").tag("endpoint", "/infer").tag("reason", "timeout")
                    .counter().count()).isEqualTo(1.0);
        } finally {
            release.countDown();
            ml.stop(0);
        }
    }
}
