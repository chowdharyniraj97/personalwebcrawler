package dev.crawler.seedloader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Loads a file of URLs, one per line, into the {@code incoming} topic. */
@Slf4j
@UtilityClass
public class Main {
  private final String DEFAULT_BOOTSTRAP = "localhost:19092";
  private final String INCOMING_TOPIC = "incoming";
  private final int EXIT_USAGE = 2;

  public void main(String[] args) throws IOException {
    if (args.length != 1) {
      log.error("Usage: seed-loader <file of URLs>");
      System.exit(EXIT_USAGE);
    }

    var result = load(Path.of(args[0]));
    log.info("Sent {}, invalid {}, failed {}", result.sent(), result.invalid(), result.failed());
    if (result.failed() > 0) {
      System.exit(1);
    }
  }

  private SeedLoader.Result load(Path file) throws IOException {
    var bootstrap = System.getenv().getOrDefault("CRAWLER_BOOTSTRAP_SERVERS", DEFAULT_BOOTSTRAP);
    Map<String, Object> config =
        Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
            bootstrap,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class.getName(),
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            ByteArraySerializer.class.getName(),
            ProducerConfig.ACKS_CONFIG,
            "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
            true,
            ProducerConfig.COMPRESSION_TYPE_CONFIG,
            "zstd");

    try (var producer = new KafkaProducer<String, byte[]>(config);
        var lines = Files.lines(file, StandardCharsets.UTF_8)) {
      return new SeedLoader(producer, INCOMING_TOPIC, Clock.systemUTC()).load(lines);
    }
  }
}
