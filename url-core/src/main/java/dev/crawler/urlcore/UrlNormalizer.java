package dev.crawler.urlcore;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;

/**
 * Rewrites URLs into one canonical form so duplicates share a fingerprint.
 *
 * <p>When in doubt it keeps URLs distinct: a duplicate fetch is acceptable, a lost URL is not. Path
 * case and trailing slashes are therefore preserved.
 */
@UtilityClass
public class UrlNormalizer {
  /** Session parameters removed by default. Names must be lowercase. */
  public final Set<String> DEFAULT_STRIPPED_PARAMS =
      Set.of("jsessionid", "phpsessid", "sessionid", "session_id", "aspsessionid", "cfid", "cftoken");

  private final String TRACKING_PREFIX = "utm_";
  private final Pattern PORT = Pattern.compile("[0-9]{1,5}");
  private final int MAX_PORT = 65_535;

  public Optional<String> normalize(String rawUrl) {
    return normalize(rawUrl, DEFAULT_STRIPPED_PARAMS);
  }

  /**
   * Returns the canonical URL, or empty if it is malformed or not http(s).
   *
   * @param strippedParams lowercase query and path parameter names to remove
   */
  public Optional<String> normalize(String rawUrl, Set<String> strippedParams) {
    URI uri;
    try {
      uri = new URI(rawUrl.strip().replace(" ", "%20"));
    } catch (URISyntaxException e) {
      return Optional.empty();
    }

    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!(scheme.equals("http") || scheme.equals("https")) || uri.getRawAuthority() == null) {
      return Optional.empty();
    }

    return normalizeAuthority(scheme, uri.getRawAuthority())
        .map(
            authority ->
                scheme
                    + "://"
                    + authority
                    + normalizePath(uri.getRawPath(), strippedParams)
                    + normalizeQuery(uri.getRawQuery(), strippedParams));
  }

  private Optional<String> normalizeAuthority(String scheme, String rawAuthority) {
    // Credentials never belong in the frontier.
    String hostAndPort = rawAuthority.substring(rawAuthority.lastIndexOf('@') + 1);

    // The colons inside an IPv6 literal such as [::1] are not port separators.
    int separator = hostAndPort.lastIndexOf(':');
    boolean hasPort = separator >= 0 && separator > hostAndPort.lastIndexOf(']');
    String host = hasPort ? hostAndPort.substring(0, separator) : hostAndPort;
    String portText = hasPort ? hostAndPort.substring(separator + 1) : "";

    if (!portText.isEmpty() && !PORT.matcher(portText).matches()) {
      return Optional.empty();
    }
    int port = portText.isEmpty() ? -1 : Integer.parseInt(portText);
    if (!portText.isEmpty() && (port == 0 || port > MAX_PORT)) {
      return Optional.empty();
    }

    return normalizeHost(host).map(h -> isDefaultPort(scheme, port) ? h : h + ":" + port);
  }

  private Optional<String> normalizeHost(String rawHost) {
    String host = rawHost.toLowerCase(Locale.ROOT);
    if (host.startsWith("[")) {
      return Optional.of(host);
    }
    if (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    if (host.isEmpty() || host.indexOf('%') >= 0 || host.indexOf(':') >= 0) {
      return Optional.empty();
    }
    try {
      return Optional.of(IDN.toASCII(host));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private boolean isDefaultPort(String scheme, int port) {
    return port == -1
        || (scheme.equals("http") && port == 80)
        || (scheme.equals("https") && port == 443);
  }

  /** Resolves dot segments per RFC 3986 and removes stripped path parameters. */
  private String normalizePath(String rawPath, Set<String> strippedParams) {
    if (rawPath.isEmpty()) {
      return "/";
    }

    String[] segments = rawPath.split("/", -1);
    Deque<String> output = new ArrayDeque<>();
    for (int i = 1; i < segments.length; i++) {
      String segment = stripPathParams(segments[i], strippedParams);
      boolean last = i == segments.length - 1;
      if (segment.equals("..")) {
        output.pollLast();
      }
      if (segment.equals(".") || segment.equals("..")) {
        if (last) {
          output.addLast("");
        }
      } else {
        output.addLast(segment);
      }
    }
    return "/" + String.join("/", output);
  }

  private String stripPathParams(String segment, Set<String> strippedParams) {
    if (segment.indexOf(';') < 0) {
      return segment;
    }
    String[] parts = segment.split(";", -1);
    return Stream.concat(
            Stream.of(parts[0]),
            Arrays.stream(parts).skip(1).filter(part -> !isStripped(part, strippedParams)))
        .collect(Collectors.joining(";"));
  }

  private String normalizeQuery(String rawQuery, Set<String> strippedParams) {
    if (rawQuery == null) {
      return "";
    }
    String kept =
        Arrays.stream(rawQuery.split("&"))
            .filter(param -> !param.isEmpty() && !isStripped(param, strippedParams))
            .sorted()
            .collect(Collectors.joining("&"));
    return kept.isEmpty() ? "" : "?" + kept;
  }

  private boolean isStripped(String param, Set<String> strippedParams) {
    int equals = param.indexOf('=');
    String name = (equals < 0 ? param : param.substring(0, equals)).toLowerCase(Locale.ROOT);
    return name.startsWith(TRACKING_PREFIX) || strippedParams.contains(name);
  }
}
