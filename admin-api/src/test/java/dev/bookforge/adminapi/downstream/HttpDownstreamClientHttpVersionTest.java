package dev.bookforge.adminapi.downstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.bookforge.adminapi.config.DownstreamProperties;
import dev.bookforge.adminapi.config.ServiceJwtProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 (31 Oct 2026): the JDK client asks plain-HTTP servers to upgrade to HTTP/2 by default. Uvicorn
 * cannot, and answered the curator's POST routes with 400 before they ran.
 */
class HttpDownstreamClientHttpVersionTest {

    private HttpServer curator;
    private final AtomicReference<List<String>> upgradeHeader = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        curator = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        curator.createContext("/", exchange -> {
            upgradeHeader.set(exchange.getRequestHeaders().get("Upgrade"));
            byte[] body = "{\"strikes\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        curator.start();
    }

    @AfterEach
    void stop() {
        curator.stop(0);
    }

    @Test
    void aPostAsksForNoProtocolUpgrade() {
        ObjectMapper mapper = new ObjectMapper();
        String base = "http://localhost:" + curator.getAddress().getPort();
        HttpDownstreamClient client = new HttpDownstreamClient(
                new DownstreamProperties(base, base, base, base),
                new ServiceAccountTokenFactory(
                        new ServiceJwtProperties("dev-only-hs256-shared-secret-change-me",
                                "bookforge-internal", "admin-api"), mapper),
                mapper, Duration.ofSeconds(5));

        client.resetContentStrikes(UUID.randomUUID(), UUID.randomUUID(), "a reason");

        assertThat(upgradeHeader.get()).isNull();
    }
}
