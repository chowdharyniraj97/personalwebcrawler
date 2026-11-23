package dev.crawler.frontier;

import dev.crawler.frontier.proto.FrontierRecord;
import java.util.Objects;
import lombok.experimental.UtilityClass;

/** Creates {@link FrontierRecord}s in the states the crawl cycle starts from. */
@UtilityClass
public class FrontierRecords {

  /** A newly discovered URL, due for its first fetch at {@code nowMs}. */
  public FrontierRecord scheduled(String url, long nowMs) {
    Objects.requireNonNull(url, "url");
    if (url.isEmpty()) {
      throw new IllegalArgumentException("url must not be empty");
    }
    if (nowMs < 0) {
      throw new IllegalArgumentException("nowMs must not be negative: %d".formatted(nowMs));
    }
    return FrontierRecord.newBuilder()
        .setUrl(url)
        .setState(FrontierRecord.State.STATE_SCHEDULED)
        .setNextDueMs(nowMs)
        .build();
  }
}
