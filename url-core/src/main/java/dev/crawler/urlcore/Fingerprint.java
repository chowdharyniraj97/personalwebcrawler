package dev.crawler.urlcore;

import java.nio.charset.StandardCharsets;
import lombok.experimental.UtilityClass;
import net.openhft.hashing.LongHashFunction;

/** Computes the 64-bit identity of a URL, the key of its frontier record. */
@UtilityClass
public class Fingerprint {
  private final LongHashFunction XXH3 = LongHashFunction.xx3();

  /**
   * Hashes the URL's UTF-8 bytes with XXH3-64. The result is persisted, so changing the algorithm,
   * seed or encoding invalidates every stored fingerprint.
   */
  public long of(String normalizedUrl) {
    return XXH3.hashBytes(normalizedUrl.getBytes(StandardCharsets.UTF_8));
  }
}
