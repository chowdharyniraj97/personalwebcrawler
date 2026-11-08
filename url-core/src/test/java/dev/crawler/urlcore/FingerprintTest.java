package dev.crawler.urlcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FingerprintTest {

  @Test
  void matchesTheXxh3ReferenceVector() {
    assertEquals(0x2D06800538D394C2L, Fingerprint.of(""));
  }

  @Test
  void sameUrlHasTheSameFingerprint() {
    assertEquals(Fingerprint.of("http://example.com/a"), Fingerprint.of("http://example.com/a"));
  }

  @Test
  void differentUrlsHaveDifferentFingerprints() {
    assertNotEquals(Fingerprint.of("http://example.com/a"), Fingerprint.of("http://example.com/b"));
  }

  @Test
  void manySimilarUrlsDoNotCollide() {
    Set<Long> fingerprints = new HashSet<>();
    for (int i = 0; i < 100_000; i++) {
      fingerprints.add(Fingerprint.of("http://example.com/p/" + i));
    }
    assertEquals(100_000, fingerprints.size());
  }
}
