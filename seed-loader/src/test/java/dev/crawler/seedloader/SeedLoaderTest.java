package dev.crawler.seedloader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.crawler.urlcore.proto.DiscoveredUrl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.clients.producer.RoundRobinPartitioner;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SeedLoaderTest {
  private static final String TOPIC = "incoming";
  private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC);

  private final MockProducer<String, byte[]> producer =
      new MockProducer<>(
          true, new RoundRobinPartitioner(), new StringSerializer(), new ByteArraySerializer());

  private SeedLoader.Result load(String... lines) {
    return new SeedLoader(producer, TOPIC, CLOCK).load(Stream.of(lines));
  }

  /** Accepts every send but reports failure for URLs on the given host. */
  private static final class FailingProducer extends MockProducer<String, byte[]> {
    private final String failingKey;

    FailingProducer(String failingKey) {
      super(true, new RoundRobinPartitioner(), new StringSerializer(), new ByteArraySerializer());
      this.failingKey = failingKey;
    }

    @Override
    public synchronized Future<RecordMetadata> send(
        ProducerRecord<String, byte[]> record, Callback callback) {
      if (record.key().equals(failingKey)) {
        var error = new TimeoutException("broker unavailable");
        callback.onCompletion(null, error);
        return CompletableFuture.failedFuture(error);
      }
      return super.send(record, callback);
    }
  }

  @Test
  void publishesTheNormalizedUrlKeyedByRegisteredDomain() throws InvalidProtocolBufferException {
    var result = load("HTTP://WWW.Example.COM:80/a/../b?utm_source=mail");

    assertEquals(new SeedLoader.Result(1, 0, 0), result);
    var record = producer.history().getFirst();
    assertEquals(TOPIC, record.topic());
    assertEquals("example.com", record.key());

    var message = DiscoveredUrl.parseFrom(record.value());
    assertEquals("http://www.example.com/b", message.getUrl());
    assertEquals("seed", message.getSource());
    assertEquals(0, message.getDepth());
    assertEquals(1_000, message.getDiscoveredAtMs());
  }

  @Test
  void givesEveryUrlOfADomainTheSameKey() {
    load("http://a.example.com/1", "http://b.example.com/2", "https://example.com/3");

    assertEquals(
        List.of("example.com", "example.com", "example.com"),
        producer.history().stream().map(ProducerRecord::key).toList());
  }

  @Test
  void countsInvalidLinesAndKeepsGoing() {
    var result = load("ftp://example.com/file", "http://example.com/ok", "http://");

    assertEquals(new SeedLoader.Result(1, 2, 0), result);
    assertEquals(1, producer.history().size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "\t"})
  void ignoresBlankLinesWithoutCountingThemAsInvalid(String blank) {
    assertEquals(new SeedLoader.Result(0, 0, 0), load(blank));
    assertTrue(producer.history().isEmpty());
  }

  @Test
  void stripsSurroundingWhitespace() {
    var result = load("  http://example.com/a  ");

    assertEquals(new SeedLoader.Result(1, 0, 0), result);
  }

  @Test
  void loadsNothingFromAnEmptyFile() {
    assertEquals(new SeedLoader.Result(0, 0, 0), load());
  }

  @Test
  void flushesSoNothingIsLeftUnsent() {
    load("http://example.com/a");

    assertTrue(producer.flushed());
  }

  @Test
  void countsUrlsTheBrokerCouldNotAccept() {
    var failing = new FailingProducer("bad.com");

    var result =
        new SeedLoader(failing, TOPIC, CLOCK)
            .load(Stream.of("http://good.com/1", "http://bad.com/1", "http://bad.com/2"));

    assertEquals(new SeedLoader.Result(1, 0, 2), result);
  }
}
