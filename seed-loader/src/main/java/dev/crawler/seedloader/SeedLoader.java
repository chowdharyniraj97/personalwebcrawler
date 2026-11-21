package dev.crawler.seedloader;

import dev.crawler.urlcore.RegisteredDomain;
import dev.crawler.urlcore.UrlNormalizer;
import dev.crawler.urlcore.proto.DiscoveredUrl;
import java.time.Clock;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

/** Normalizes seed URLs and publishes them to Kafka, keyed by registered domain. */
@Slf4j
@RequiredArgsConstructor
public class SeedLoader {
  private static final int SEED_DEPTH = 0;

  private final Producer<String, byte[]> producer;
  private final String topic;
  private final Clock clock;

  /**
   * Totals for one load.
   *
   * @param sent URLs the broker confirmed
   * @param invalid lines that were not a valid http(s) URL
   * @param failed URLs the producer could not deliver
   */
  public record Result(long sent, long invalid, long failed) {}

  /**
   * Publishes every valid URL and waits until the broker has answered for all of them.
   *
   * <p>The main thread does the sending, but the producer's network thread runs the callbacks, so
   * the counters must be safe to update from another thread.
   */
  public Result load(Stream<String> lines) {
    var sent = new LongAdder();
    var invalid = new LongAdder();
    var failed = new LongAdder();

    lines
        .map(String::strip)
        .filter(Predicate.not(String::isEmpty))
        .forEach(line ->
            UrlNormalizer.normalize(line)
                .ifPresentOrElse(url -> send(url, sent, failed), invalid::increment));

    producer.flush();
    return new Result(sent.sum(), invalid.sum(), failed.sum());
  }

  private void send(String url, LongAdder sent, LongAdder failed) {
    var message =
        DiscoveredUrl.newBuilder()
            .setUrl(url)
            .setDepth(SEED_DEPTH)
            .setDiscoveredAtMs(clock.millis())
            .build();
    var record = new ProducerRecord<>(topic, RegisteredDomain.ofUrl(url), message.toByteArray());

    producer.send(
        record,
        (metadata, error) -> {
          if (error == null) {
            sent.increment();
            return;
          }
          // An outage fails every URL, so log only the first one.
          if (failed.sum() == 0) {
            log.warn("Could not deliver {}", url, error);
          }
          failed.increment();
        });
  }
}
