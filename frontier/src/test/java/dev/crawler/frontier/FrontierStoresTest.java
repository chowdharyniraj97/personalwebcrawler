package dev.crawler.frontier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.crawler.frontier.proto.FrontierRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.rocksdb.RocksDBException;

/** Runs against real RocksDB in a temporary folder. */
class FrontierStoresTest {
  private static final long NOW_MS = 1_700_000_000_000L;
  private static final long CACHE_BYTES = 64L << 20;
  private static final long WRITE_BUFFER_BYTES = 32L << 20;

  @TempDir Path root;
  private FrontierStores stores;

  @BeforeEach
  void createStores() throws IOException {
    stores = FrontierStores.create(root, CACHE_BYTES, WRITE_BUFFER_BYTES);
  }

  @AfterEach
  void closeStores() {
    stores.close();
  }

  private static FrontierRecord record(long fingerprint) {
    return FrontierRecords.scheduled("http://site1.test/p/" + fingerprint, NOW_MS);
  }

  @Test
  void opensAStoreAndGetsTheSameOneBack() throws RocksDBException {
    var opened = stores.open(3);

    assertSame(opened, stores.get(3));
    assertTrue(Files.isDirectory(root.resolve("partition-3")));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, Integer.MAX_VALUE})
  void opensEdgePartitions(int partition) throws RocksDBException {
    stores.open(partition).insertAll(Map.of(1L, record(1)));

    assertEquals(Optional.of(record(1)), stores.get(partition).get(1));
  }

  @Test
  void keepsPartitionsSeparate() throws RocksDBException {
    stores.open(0).insertAll(Map.of(42L, record(42)));
    stores.open(1);

    assertEquals(Set.of(42L), stores.get(1).findAbsent(List.of(42L)));
  }

  @Test
  void refusesToOpenAPartitionTwiceAndKeepsTheFirstUsable() throws RocksDBException {
    var first = stores.open(3);

    assertThrows(IllegalStateException.class, () -> stores.open(3));
    assertSame(first, stores.get(3));
    first.insertAll(Map.of(1L, record(1)));
  }

  @Test
  void refusesToGetAPartitionThatIsNotOpen() throws RocksDBException {
    assertThrows(IllegalStateException.class, () -> stores.get(3));

    stores.open(3);
    stores.close(3);
    assertThrows(IllegalStateException.class, () -> stores.get(3));
  }

  @Test
  void rejectsNegativePartitions() {
    assertThrows(IllegalArgumentException.class, () -> stores.open(-1));
    assertThrows(IllegalArgumentException.class, () -> stores.get(-1));
    assertThrows(IllegalArgumentException.class, () -> stores.close(-1));
    assertThrows(IllegalArgumentException.class, () -> stores.delete(-1));
  }

  @Test
  void keepsDataAfterCloseAndReopen() throws RocksDBException {
    stores.open(3).insertAll(Map.of(42L, record(42)));
    stores.close(3);

    assertEquals(Optional.of(record(42)), stores.open(3).get(42));
  }

  @Test
  void deletesAPartitionsData() throws RocksDBException, IOException {
    stores.open(3).insertAll(Map.of(42L, record(42)));

    stores.delete(3);

    assertFalse(Files.exists(root.resolve("partition-3")));
    assertEquals(Optional.empty(), stores.open(3).get(42));
  }

  @Test
  void deletesAFolderLeftByAnEarlierRun() throws RocksDBException, IOException {
    stores.open(3).insertAll(Map.of(42L, record(42)));
    stores.close(3);

    stores.delete(3);

    assertFalse(Files.exists(root.resolve("partition-3")));
  }

  @Test
  void closingOrDeletingAnUnknownPartitionDoesNothing() {
    assertDoesNotThrow(() -> stores.close(7));
    assertDoesNotThrow(() -> stores.delete(7));
  }

  @Test
  void closesEveryStoreSoAnotherInstanceCanOpenThem() throws RocksDBException, IOException {
    stores.open(0).insertAll(Map.of(1L, record(1)));
    stores.open(1).insertAll(Map.of(2L, record(2)));

    stores.close();
    stores = FrontierStores.create(root, CACHE_BYTES, WRITE_BUFFER_BYTES);

    assertEquals(Optional.of(record(1)), stores.open(0).get(1));
    assertEquals(Optional.of(record(2)), stores.open(1).get(2));
  }

  /** Writes to every store's memtables are counted in the one shared cache. */
  @Test
  void countsEveryStoresMemtablesInTheSharedCache() throws RocksDBException {
    var p0 = stores.open(0);
    var p1 = stores.open(1);
    var before = stores.memoryUsage();

    p0.insertAll(batch(0, 20_000));
    var afterOne = stores.memoryUsage();
    p1.insertAll(batch(20_000, 40_000));
    var afterTwo = stores.memoryUsage();

    assertTrue(afterOne > before, "%d then %d".formatted(before, afterOne));
    assertTrue(afterTwo > afterOne, "%d then %d".formatted(afterOne, afterTwo));
  }

  @ParameterizedTest
  @CsvSource({"0, 0", "-1, 1", "1, 0", "1, -1", "1, 2"})
  void rejectsSizesOutsideTheBudget(long cacheBytes, long writeBufferBytes) {
    assertThrows(
        IllegalArgumentException.class,
        () -> FrontierStores.create(root.resolve("other"), cacheBytes, writeBufferBytes));
  }

  @Test
  void acceptsMemtablesUsingTheWholeBudget() throws IOException, RocksDBException {
    try (var whole = FrontierStores.create(root.resolve("other"), CACHE_BYTES, CACHE_BYTES)) {
      whole.open(0).insertAll(Map.of(1L, record(1)));

      assertEquals(Optional.of(record(1)), whole.get(0).get(1));
    }
  }

  private static Map<Long, FrontierRecord> batch(long from, long to) {
    return LongStream.range(from, to)
        .boxed()
        .collect(Collectors.toMap(Function.identity(), FrontierStoresTest::record));
  }
}
