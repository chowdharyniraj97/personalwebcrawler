package dev.crawler.frontier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.crawler.frontier.proto.FrontierRecord;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class FrontierRecordsTest {
  private static final String URL = "http://site1.test/p/1";
  private static final long NOW_MS = 1_700_000_000_000L;

  @Test
  void schedulesANewUrlForNowWithEverythingElseEmpty() {
    var record = FrontierRecords.scheduled(URL, NOW_MS);

    assertEquals(URL, record.getUrl());
    assertEquals(FrontierRecord.State.STATE_SCHEDULED, record.getState());
    assertEquals(NOW_MS, record.getNextDueMs());
    assertEquals(0, record.getLastCrawlMs());
    assertEquals(0, record.getFailCount());
    assertEquals("", record.getEtag());
  }

  @Test
  void survivesTheTripThroughBytes() throws InvalidProtocolBufferException {
    var record = FrontierRecords.scheduled(URL, NOW_MS);

    assertEquals(record, FrontierRecord.parseFrom(record.toByteArray()));
  }

  @Test
  void storesANewRecordAsLittleMoreThanItsUrl() {
    // State takes 2 bytes, the time 7, and the URL 2 plus its length: empty fields cost nothing.
    assertEquals(9 + 2 + URL.length(), FrontierRecords.scheduled(URL, NOW_MS).toByteArray().length);
  }

  @Test
  void acceptsTimeZero() {
    var record = FrontierRecords.scheduled(URL, 0);

    assertEquals(0, record.getNextDueMs());
    assertEquals(2 + 2 + URL.length(), record.toByteArray().length);
  }

  @Test
  void rejectsANegativeTime() {
    assertThrows(IllegalArgumentException.class, () -> FrontierRecords.scheduled(URL, -1));
  }

  @Test
  void rejectsAMissingOrEmptyUrl() {
    assertThrows(NullPointerException.class, () -> FrontierRecords.scheduled(null, NOW_MS));
    assertThrows(IllegalArgumentException.class, () -> FrontierRecords.scheduled("", NOW_MS));
  }

  @Test
  void aFullRecordIsTheUrlPlusAFewDozenBytes() throws InvalidProtocolBufferException {
    var record =
        FrontierRecord.newBuilder()
            .setUrl(URL)
            .setState(FrontierRecord.State.STATE_FETCHED)
            .setNextDueMs(NOW_MS + 86_400_000)
            .setLastCrawlMs(NOW_MS)
            .setContentHash(0x9e37_79b9_7f4a_7c15L)
            .setFailCount(3)
            .setEtag("\"33a64df5\"")
            .build();
    var bytes = record.toByteArray();

    assertEquals(record, FrontierRecord.parseFrom(bytes));
    var overhead = bytes.length - URL.length();
    assertTrue(overhead <= 60, "overhead was %d bytes".formatted(overhead));
  }

  /** A record written by a later version, with a field this code does not know, still reads. */
  @Test
  void readsRecordsThatHaveFieldsAddedLater() throws InvalidProtocolBufferException {
    var current = FrontierRecords.scheduled(URL, NOW_MS).toByteArray();
    var fieldNinetyNine = new byte[] {(byte) 0x98, 0x06, 0x01}; // field 99, varint 1
    var future = Arrays.copyOf(current, current.length + fieldNinetyNine.length);
    System.arraycopy(fieldNinetyNine, 0, future, current.length, fieldNinetyNine.length);

    var read = FrontierRecord.parseFrom(future);

    assertEquals(URL, read.getUrl());
    assertEquals(NOW_MS, read.getNextDueMs());
  }

  @Test
  void rejectsCorruptBytes() {
    var truncated = Arrays.copyOf(FrontierRecords.scheduled(URL, NOW_MS).toByteArray(), 4);

    assertThrows(InvalidProtocolBufferException.class, () -> FrontierRecord.parseFrom(truncated));
  }
}
