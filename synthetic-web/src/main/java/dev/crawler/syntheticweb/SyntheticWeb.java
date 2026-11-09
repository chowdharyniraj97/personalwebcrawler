package dev.crawler.syntheticweb;

import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A deterministic fake internet. Every page is derived from the seed, domain and page number, so it
 * can serve millions of URLs without storing any of them.
 *
 * <p>Domain behavior is chosen by index: {@code % 5 == 0} sets a crawl-delay, {@code % 10 == 3}
 * returns 429 on every fifth request, and {@code % 20 == 7} contains an infinite URL trap.
 */
public final class SyntheticWeb {
  private static final Pattern HOST = Pattern.compile("site(\\d{1,7})\\.test(:\\d+)?");
  private static final Pattern PAGE = Pattern.compile("/p/(\\d{1,9})");
  private static final String HTML = "text/html; charset=utf-8";
  private static final String TEXT = "text/plain; charset=utf-8";
  private static final int CROSS_DOMAIN_LINK_PERCENT = 20;
  private static final int RATE_LIMIT_EVERY = 5;
  private static final String ROBOTS =
      """
      User-agent: *
      Disallow: /private/
      """;
  private static final String CRAWL_DELAY = "Crawl-delay: 2\n";

  // Links every page repeats, like a real site's navigation.
  private static final String NAVIGATION =
      "<a href=\"/\">home</a> <a href=\"/p/1\">about</a> <a href=\"/private/admin\">admin</a>";
  private static final String TRAP_LINK = "<a href=\"/trap/a/\">calendar</a>";

  private final Config config;
  private final ConcurrentHashMap<Integer, AtomicLong> requestsByDomain = new ConcurrentHashMap<>();

  public SyntheticWeb(Config config) {
    this.config = config;
  }

  public record Config(int domains, int pagesPerDomain, int linksPerPage, long seed) {}

  public record Response(int status, String contentType, String body, Map<String, String> headers) {
    static Response of(int status, String contentType, String body) {
      return new Response(status, contentType, body, Map.of());
    }
  }

  public Response respond(String host, String path) {
    var domain = domainIndex(host);
    if (domain < 0) {
      return notFound();
    }
    if (path.equals("/robots.txt")) {
      return Response.of(200, TEXT, domain % 5 == 0 ? ROBOTS + CRAWL_DELAY : ROBOTS);
    }
    if (shouldRateLimit(domain)) {
      return new Response(429, TEXT, "slow down\n", Map.of("Retry-After", "1"));
    }
    return switch (path) {
      case "/" -> page(domain, 0);
      case String p when p.startsWith("/private/") -> html("disallowed by robots.txt");
      case String p when isTrap(domain) && p.startsWith("/trap/") ->
          html("<a href=\"%sa/\">deeper</a>".formatted(p));
      default -> numberedPage(domain, path);
    };
  }

  private int domainIndex(String host) {
    if (host == null) {
      return -1;
    }
    var matcher = HOST.matcher(host.toLowerCase(Locale.ROOT));
    if (!matcher.matches()) {
      return -1;
    }
    var domain = Integer.parseInt(matcher.group(1));
    return domain < config.domains() ? domain : -1;
  }

  private boolean shouldRateLimit(int domain) {
    if (domain % 10 != 3) {
      return false;
    }
    var request =
        requestsByDomain.computeIfAbsent(domain, key -> new AtomicLong()).incrementAndGet();
    return request % RATE_LIMIT_EVERY == 0;
  }

  private static boolean isTrap(int domain) {
    return domain % 20 == 7;
  }

  private Response numberedPage(int domain, String path) {
    var matcher = PAGE.matcher(path);
    if (!matcher.matches()) {
      return notFound();
    }
    var page = Integer.parseInt(matcher.group(1));
    return page < config.pagesPerDomain() ? page(domain, page) : notFound();
  }

  private Response page(int domain, int page) {
    var random = new SplittableRandom(config.seed() ^ (((long) domain << 32) | page));
    var links =
        IntStream.range(0, config.linksPerPage())
            .mapToObj(i -> randomLink(random))
            .collect(Collectors.joining(" "));
    return html("%s %s %s".formatted(NAVIGATION, isTrap(domain) ? TRAP_LINK : "", links));
  }

  private String randomLink(SplittableRandom random) {
    var target = random.nextInt(config.pagesPerDomain());
    if (random.nextInt(100) < CROSS_DOMAIN_LINK_PERCENT) {
      var other = random.nextInt(config.domains());
      return "<a href=\"http://site%d.test/p/%d\">x</a>".formatted(other, target);
    }
    return "<a href=\"/p/%d\">y</a>".formatted(target);
  }

  private static Response html(String body) {
    return Response.of(200, HTML, "<html><body>%s</body></html>".formatted(body));
  }

  private static Response notFound() {
    return Response.of(404, TEXT, "not found\n");
  }
}
