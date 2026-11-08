package dev.crawler.urlcore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.crawler.urlcore.proto.DiscoveredUrl;
import org.junit.jupiter.api.Test;

class DiscoveredUrlTest {

  @Test
  void survivesARoundTrip() throws InvalidProtocolBufferException {
    DiscoveredUrl message =
        DiscoveredUrl.newBuilder()
            .setUrl("http://example.com/a")
            .setSource("http://example.com/")
            .setDepth(3)
            .setDiscoveredAtMs(1_762_560_000_000L)
            .build();

    assertEquals(message, DiscoveredUrl.parseFrom(message.toByteArray()));
  }

  @Test
  void aSeedHasNoSourceAndDepthZero() throws InvalidProtocolBufferException {
    byte[] bytes = DiscoveredUrl.newBuilder().setUrl("http://example.com/").build().toByteArray();

    DiscoveredUrl parsed = DiscoveredUrl.parseFrom(bytes);

    assertEquals("", parsed.getSource());
    assertEquals(0, parsed.getDepth());
  }

  @Test
  void unsetFieldsTakeNoSpace() {
    String url = "http://example.com/";
    int tagAndLength = 2;

    assertEquals(
        url.length() + tagAndLength,
        DiscoveredUrl.newBuilder().setUrl(url).build().getSerializedSize());
  }
}
