package dev.crawler.urlcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RegisteredDomainTest {
  private static final String BUNDLED_LIST = "/effective_tld_names.dat";

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "example.com, example.com",
    "www.example.com, example.com",
    "a.b.c.example.com, example.com",
    "blog.example.co.uk, example.co.uk",
    "shop.example.co.uk, example.co.uk",
    "foo.github.io, github.io",
    "www.example.xn--p1ai, example.xn--p1ai",
    "foo.bar.kawasaki.jp, foo.bar.kawasaki.jp",
    "www.city.kawasaki.jp, city.kawasaki.jp",
    "site1.test, site1.test",
    "www.site1.test, www.site1.test",
    "co.uk, co.uk",
    "com, com",
    "localhost, localhost",
    "127.0.0.1, 127.0.0.1",
    "'[::1]', '[::1]'",
  })
  void findsTheRegisteredDomain(String host, String expected) {
    assertEquals(expected, RegisteredDomain.of(host));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "http://blog.example.co.uk/a, example.co.uk",
    "https://blog.example.co.uk:8443/a?x=1, example.co.uk",
    "http://example.com/, example.com",
    "http://127.0.0.1:8080/a, 127.0.0.1",
    "'http://[::1]/a', '[::1]'",
    "'http://[::1]:8080/a', '[::1]'",
  })
  void findsTheRegisteredDomainOfAUrl(String url, String expected) {
    assertEquals(expected, RegisteredDomain.ofUrl(url));
  }

  @Test
  void aNewerSuffixListTakesEffect() throws IOException {
    assertEquals("a.b.corp.example", RegisteredDomain.of("a.b.corp.example"));
    try {
      byte[] extended = concat(bundledList(), "\ncorp.example\n");
      assertTrue(RegisteredDomain.reload(new ByteArrayInputStream(extended)));
      assertEquals("b.corp.example", RegisteredDomain.of("a.b.corp.example"));
    } finally {
      assertTrue(RegisteredDomain.reload(new ByteArrayInputStream(bundledList())));
    }
  }

  private static byte[] bundledList() throws IOException {
    try (InputStream list = RegisteredDomainTest.class.getResourceAsStream(BUNDLED_LIST)) {
      return list.readAllBytes();
    }
  }

  private static byte[] concat(byte[] list, String extraRule) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.write(list);
    output.write(extraRule.getBytes(StandardCharsets.UTF_8));
    return output.toByteArray();
  }
}
