package dev.crawler.frontier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.crawler.frontier.proto.FrontierRecord;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.rocksdb.RocksDBException;

/** Runs against real RocksDB in a temporary folder. */
class PartitionStoreTest {
  private static final long NOW_MS = 1_700_000_000_000L;

  @TempDir Path folder;

  private static FrontierRecord record(long fingerprint) {
    return record(fingerprint, NOW_MS);
  }

  private static FrontierRecord record(long fingerprint, long dueMs) {
    return FrontierRecords.scheduled("http://site1.test/p/" + fingerprint, dueMs);
  }

  private static Map<Long, FrontierRecord> records(long... fingerprints) {
    return Arrays.stream(fingerprints)
        .boxed()
        .collect(Collectors.toMap(Function.identity(), PartitionStoreTest::record));
  }

  private PartitionStore open(String name) throws RocksDBException {
    return PartitionStore.open(folder.resolve(name));
  }

  @Test
  void findsEverythingAbsentInANewStore() throws RocksDBException {
    try (var store = open("p0")) {
      assertEquals(Set.of(1L, 2L, 3L), store.findAbsent(List.of(1L, 2L, 3L)));
    }
  }

  @Test
  void findsOnlyTheFingerprintsNotYetStored() throws RocksDBException {
    try (var store = open("p0")) {
      store.insertAll(records(1, 2));

      assertEquals(Set.of(3L, 4L), store.findAbsent(List.of(1L, 2L, 3L, 4L)));
    }
  }

  @Test
  void checksARepeatedFingerprintOnce() throws RocksDBException {
    try (var store = open("p0")) {
      assertEquals(Set.of(7L), store.findAbsent(List.of(7L, 7L, 7L)));
    }
  }

  @Test
  void findsNothingInAnEmptyBatch() throws RocksDBException {
    try (var store = open("p0")) {
      assertEquals(Set.of(), store.findAbsent(List.of()));
    }
  }

  @Test
  void rejectsAMissingFingerprint() throws RocksDBException {
    try (var store = open("p0")) {
      assertThrows(NullPointerException.class, () -> store.findAbsent(Arrays.asList(1L, null)));
    }
  }

  @Test
  void insertsABatchAndReadsItBack() throws RocksDBException {
    try (var store = open("p0")) {
      store.insertAll(records(1, 2, 3));

      assertEquals(Optional.of(record(2)), store.get(2));
      assertEquals(Set.of(), store.findAbsent(List.of(1L, 2L, 3L)));
    }
  }

  @Test
  void insertsABatchOfOne() throws RocksDBException {
    try (var store = open("p0")) {
      store.insertAll(records(42));

      assertEquals(Optional.of(record(42)), store.get(42));
    }
  }

  @Test
  void insertsNothingForAnEmptyBatch() throws RocksDBException {
    try (var store = open("p0")) {
      store.insertAll(Map.of());

      assertEquals(Set.of(1L), store.findAbsent(List.of(1L)));
    }
  }

  @Test
  void indexesEveryRecordByItsDueTime() throws RocksDBException {
    try (var store = open("p0")) {
      store.insertAll(Map.of(1L, record(1, NOW_MS), 2L, record(2, NOW_MS + 60_000)));

      assertTrue(store.isIndexedAsDue(1, NOW_MS));
      assertTrue(store.isIndexedAsDue(2, NOW_MS + 60_000));
      assertFalse(store.isIndexedAsDue(2, NOW_MS));
    }
  }

  /** Restore writes records in any state, such as one fetched and due tomorrow. */
  @Test
  void indexesARestoredRecordAtItsOwnDueTime() throws RocksDBException {
    var tomorrow = NOW_MS + 86_400_000;
    var fetched =
        record(9, tomorrow).toBuilder()
            .setState(FrontierRecord.State.STATE_FETCHED)
            .setLastCrawlMs(NOW_MS)
            .build();
    try (var store = open("p0")) {
      store.insertAll(Map.of(9L, fetched));

      assertEquals(Optional.of(fetched), store.get(9));
      assertTrue(store.isIndexedAsDue(9, tomorrow));
    }
  }

  @Test
  void insertsALargeBatch() throws RocksDBException {
    var batch =
        LongStream.range(0, 10_000)
            .boxed()
            .collect(Collectors.toMap(Function.identity(), PartitionStoreTest::record));
    try (var store = open("p0")) {
      store.insertAll(batch);

      assertEquals(Optional.of(record(9_999)), store.get(9_999));
      assertEquals(Set.of(10_000L), store.findAbsent(List.of(0L, 5_000L, 9_999L, 10_000L)));
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, Long.MIN_VALUE, Long.MAX_VALUE})
  void handlesEdgeFingerprints(long fingerprint) throws RocksDBException {
    try (var store = open("p0")) {
      store.insertAll(records(fingerprint));

      assertEquals(Optional.of(record(fingerprint)), store.get(fingerprint));
      assertTrue(store.isIndexedAsDue(fingerprint, NOW_MS));
      assertEquals(
          Set.of(fingerprint ^ 1), store.findAbsent(List.of(fingerprint, fingerprint ^ 1)));
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {0, Long.MAX_VALUE})
  void indexesEdgeDueTimes(long dueMs) throws RocksDBException {
    try (var store = open("p0")) {
      store.insertAll(Map.of(42L, record(42, dueMs)));

      assertTrue(store.isIndexedAsDue(42, dueMs));
    }
  }

  @Test
  void writesNothingWhenAnyRecordHasANegativeDueTime() throws RocksDBException {
    var negative =
        FrontierRecord.newBuilder().setUrl("http://site1.test/bad").setNextDueMs(-1).build();
    try (var store = open("p0")) {
      var batch = Map.of(1L, record(1), 2L, negative);

      assertThrows(IllegalArgumentException.class, () -> store.insertAll(batch));
      assertEquals(Set.of(1L, 2L), store.findAbsent(List.of(1L, 2L)));
    }
  }

  @Test
  void rejectsAMissingRecordAndWritesNothing() throws RocksDBException {
    var batch = new HashMap<Long, FrontierRecord>();
    batch.put(1L, record(1));
    batch.put(2L, null);
    try (var store = open("p0")) {
      assertThrows(NullPointerException.class, () -> store.insertAll(batch));
      assertEquals(Set.of(1L), store.findAbsent(List.of(1L)));
    }
  }

  @Test
  void findsNothingForAnUnknownFingerprint() throws RocksDBException {
    try (var store = open("p0")) {
      assertEquals(Optional.empty(), store.get(42));
    }
  }

  @Test
  void keepsRecordsAndIndexAfterCloseAndReopen() throws RocksDBException {
    try (var store = open("p0")) {
      store.insertAll(records(42));
    }

    try (var reopened = open("p0")) {
      assertEquals(Optional.of(record(42)), reopened.get(42));
      assertTrue(reopened.isIndexedAsDue(42, NOW_MS));
      assertEquals(Set.of(), reopened.findAbsent(List.of(42L)));
    }
  }

  @Test
  void keepsPartitionsSeparate() throws RocksDBException {
    try (var p0 = open("p0");
        var p1 = open("p1")) {
      p0.insertAll(records(42));

      assertEquals(Set.of(42L), p1.findAbsent(List.of(42L)));
      assertEquals(Optional.empty(), p1.get(42));
    }
  }

  @Test
  void refusesASecondOpenOfTheSameFolder() throws RocksDBException {
    try (var store = open("p0")) {
      assertThrows(RocksDBException.class, () -> open("p0"));
      store.insertAll(records(42));
      assertEquals(Optional.of(record(42)), store.get(42));
    }
  }
}
