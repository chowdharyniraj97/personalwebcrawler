package dev.crawler.node;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;

/** Placeholder node: confirms it can reach the Kafka cluster. */
@Slf4j
@UtilityClass
public class Main {
  private final String DEFAULT_BOOTSTRAP = "localhost:19092";

  public void main(String[] args) throws ExecutionException, InterruptedException {
    var bootstrap = System.getenv().getOrDefault("CRAWLER_BOOTSTRAP_SERVERS", DEFAULT_BOOTSTRAP);

    try (var admin =
        Admin.create(
            Map.<String, Object>of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
      log.info("Connected to {} broker(s)", admin.describeCluster().nodes().get().size());
    }
  }
}
