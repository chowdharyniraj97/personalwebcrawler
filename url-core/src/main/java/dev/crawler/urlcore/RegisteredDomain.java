package dev.crawler.urlcore;

import crawlercommons.domains.EffectiveTldFinder;
import java.io.InputStream;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * Finds a host's registered domain (eTLD+1), which is the Kafka partition key.
 *
 * <p>Only ICANN suffixes count, so every {@code *.github.io} site lands on one owner node and
 * shares its politeness limits. Hosts with no registered domain, such as IP addresses or names
 * under a suffix missing from the list, are their own key, so a URL is never lost for lack of one.
 */
@UtilityClass
public class RegisteredDomain {
  private final Pattern IPV4 = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");

  /** Returns the registered domain of a URL produced by {@link UrlNormalizer}. */
  public String ofUrl(String normalizedUrl) {
    var start = normalizedUrl.indexOf("://") + 3;
    var authority = normalizedUrl.substring(start, normalizedUrl.indexOf('/', start));

    // The colons inside an IPv6 literal such as [::1] are not port separators.
    var separator = authority.lastIndexOf(':');
    var hasPort = separator > authority.lastIndexOf(']');
    return of(hasPort ? authority.substring(0, separator) : authority);
  }

  public String of(String host) {
    if (host.startsWith("[") || IPV4.matcher(host).matches()) {
      return host;
    }
    var strict = false;
    var excludePrivateSuffixes = true;
    String domain = EffectiveTldFinder.getAssignedDomain(host, strict, excludePrivateSuffixes);
    return domain == null ? host : domain;
  }

  /**
   * Replaces the bundled Public Suffix List with a newer copy.
   *
   * <p>Call once at startup, before any lookups. Every node must use the same list, or the same
   * domain could map to different partitions.
   *
   * @return whether the list loaded successfully
   */
  public boolean reload(InputStream suffixList) {
    return EffectiveTldFinder.getInstance().initialize(suffixList);
  }
}
