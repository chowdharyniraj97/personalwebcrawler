package dev.crawler.syntheticweb;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import lombok.experimental.UtilityClass;

/** Serves {@link SyntheticWeb} over HTTP using one virtual thread per request. */
@UtilityClass
public class Server {
  private final int BACKLOG = 4096;

  public void main(String[] args) throws IOException {
    var web =
        new SyntheticWeb(
            new SyntheticWeb.Config(
                intEnv("SYNTHETIC_DOMAINS", 1_000),
                intEnv("SYNTHETIC_PAGES_PER_DOMAIN", 10_000),
                intEnv("SYNTHETIC_LINKS_PER_PAGE", 50),
                intEnv("SYNTHETIC_SEED", 42)));
    var maxLatencyMs = intEnv("SYNTHETIC_MAX_LATENCY_MS", 50);
    var port = intEnv("SYNTHETIC_PORT", 80);

    var server = HttpServer.create(new InetSocketAddress(port), BACKLOG);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/", exchange -> handle(exchange, web, maxLatencyMs));
    server.start();
    System.out.println("Synthetic web listening on port %d".formatted(port));
  }

  private void handle(HttpExchange exchange, SyntheticWeb web, int maxLatencyMs)
      throws IOException {
    try (exchange) {
      var path = exchange.getRequestURI().getPath();
      if (path.equals("/healthz")) {
        send(exchange, new SyntheticWeb.Response(200, "text/plain", "ok\n", Map.of()));
        return;
      }
      if (maxLatencyMs > 0) {
        Thread.sleep(ThreadLocalRandom.current().nextInt(maxLatencyMs + 1));
      }
      send(exchange, web.respond(exchange.getRequestHeaders().getFirst("Host"), path));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void send(HttpExchange exchange, SyntheticWeb.Response response) throws IOException {
    var body = response.body().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", response.contentType());
    response.headers().forEach(exchange.getResponseHeaders()::set);
    exchange.sendResponseHeaders(response.status(), body.length);
    exchange.getResponseBody().write(body);
  }

  private int intEnv(String name, int fallback) {
    var value = System.getenv(name);
    return value == null ? fallback : Integer.parseInt(value);
  }
}
