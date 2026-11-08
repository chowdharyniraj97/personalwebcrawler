package dev.crawler.urlcore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

class UrlNormalizerTest {

  /** A blank expected column means the URL must be rejected. */
  @ParameterizedTest(name = "{0}")
  @CsvFileSource(resources = "/normalizer-cases.psv", delimiter = '|', numLinesToSkip = 1)
  void normalizes(String input, String expected) {
    Optional<String> actual = UrlNormalizer.normalize(input);

    assertEquals(Optional.ofNullable(expected), actual);
    actual.ifPresent(url -> assertEquals(actual, UrlNormalizer.normalize(url), "not idempotent"));
  }

  @Test
  void strippedParamsAreConfigurable() {
    assertEquals(
        Optional.of("http://example.com/a?jsessionid=1"),
        UrlNormalizer.normalize("http://example.com/a?jsessionid=1&token=2", Set.of("token")));
  }
}
