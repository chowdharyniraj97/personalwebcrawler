package dev.crawler.syntheticweb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SyntheticWebTest {
  private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");
  private static final String VALID_LINK =
      "(http://site\\d{1,2}\\.test)?/(p/\\d{1,3}|private/admin)?";
  private static final int NAVIGATION_LINKS = 3;

  private final SyntheticWeb web = new SyntheticWeb(new SyntheticWeb.Config(100, 1_000, 50, 42));

  @Test
  void pagesAreDeterministicAcrossInstances() {
    var other = new SyntheticWeb(new SyntheticWeb.Config(100, 1_000, 50, 42));

    assertEquals(
        web.respond("site1.test", "/p/9").body(), other.respond("site1.test", "/p/9").body());
  }

  @Test
  void pagesLinkWithinTheConfiguredWeb() {
    var links = links(web.respond("site1.test", "/p/9").body());

    assertEquals(NAVIGATION_LINKS + 50, links.size());
    assertTrue(links.stream().allMatch(link -> link.matches(VALID_LINK)), links::toString);
    assertTrue(links.stream().anyMatch(link -> link.startsWith("http://")));
  }

  @Test
  void zeroLinksPerPageLeavesOnlyNavigation() {
    var noLinks = new SyntheticWeb(new SyntheticWeb.Config(100, 1_000, 0, 42));

    assertEquals(NAVIGATION_LINKS, links(noLinks.respond("site1.test", "/p/9").body()).size());
  }

  @Test
  void rootIsPageZero() {
    assertEquals(web.respond("site1.test", "/p/0").body(), web.respond("site1.test", "/").body());
  }

  @Test
  void firstAndLastDomainsAndPagesExist() {
    assertEquals(200, web.respond("site0.test", "/p/0").status());
    assertEquals(200, web.respond("site99.test", "/p/999").status());
  }

  @Test
  void robotsDisallowsPrivateAndSetsCrawlDelayOnSomeDomains() {
    var plain = web.respond("site1.test", "/robots.txt").body();

    assertTrue(plain.contains("Disallow: /private/"));
    assertFalse(plain.contains("Crawl-delay"));
    assertTrue(web.respond("site5.test", "/robots.txt").body().contains("Crawl-delay: 2"));
  }

  @Test
  void rateLimitedDomainReturns429WithRetryAfterOnEveryFifthRequest() {
    var statuses = new ArrayList<Integer>();
    SyntheticWeb.Response last = null;
    for (var i = 0; i < 5; i++) {
      last = web.respond("site3.test", "/p/1");
      statuses.add(last.status());
    }

    assertEquals(List.of(200, 200, 200, 200, 429), statuses);
    assertEquals("1", last.headers().get("Retry-After"));
  }

  @Test
  void trapDomainGeneratesEndlesslyDeeperUrls() {
    assertTrue(links(web.respond("site7.test", "/p/1").body()).contains("/trap/a/"));
    assertEquals(List.of("/trap/a/a/a/"), links(web.respond("site7.test", "/trap/a/a/").body()));
  }

  @Test
  void trapPathsDoNotExistOnOtherDomains() {
    assertEquals(404, web.respond("site1.test", "/trap/a/").status());
  }

  @Test
  void unknownHostsAndPagesReturn404() {
    assertEquals(404, web.respond("site100.test", "/p/1").status());
    assertEquals(404, web.respond("example.com", "/p/1").status());
    assertEquals(404, web.respond(null, "/p/1").status());
    assertEquals(404, web.respond("site1.test", "/p/1000").status());
    assertEquals(404, web.respond("site1.test", "/unknown").status());
  }

  private static List<String> links(String html) {
    return HREF.matcher(html).results().map(match -> match.group(1)).toList();
  }
}
