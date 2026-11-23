package dev.crawler.frontier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.rocksdb.Cache;
import org.rocksdb.LRUCache;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBufferManager;

/**
 * The partition stores this node has open, each in its own folder {@code root/partition-<n>}, all
 * sharing one memory budget.
 *
 * <p><b>Memory:</b> every store reads through one shared block cache, and one write buffer manager
 * caps the memtables of all stores together. Memtable memory is counted inside the cache, so {@code
 * cacheBytes} bounds the RocksDB memory of all stores, however many are open. This is how the
 * RocksDB wiki says to cap memory across several databases in one process.
 *
 * <p><b>Threading:</b> {@link #get} is safe from any thread. {@link #open}, {@link #close(int)} and
 * {@link #delete} are called by one thread, the consumer thread that is told about partition
 * assignments. Before closing or deleting a partition, the caller must stop every thread using its
 * store: closing RocksDB while another thread is inside a call can crash the process.
 */
public final class FrontierStores implements AutoCloseable {
  static {
    RocksDB.loadLibrary();
  }

  private final Path root;
  private final Cache cache;
  private final WriteBufferManager writeBufferManager;
  private final ConcurrentMap<Integer, PartitionStore> stores = new ConcurrentHashMap<>();

  private FrontierStores(Path root, Cache cache, WriteBufferManager writeBufferManager) {
    this.root = root;
    this.cache = cache;
    this.writeBufferManager = writeBufferManager;
  }

  /**
   * Creates the root folder if needed and the shared memory, without opening any store.
   *
   * @param cacheBytes RocksDB memory for all stores together: cached reads and memtables
   * @param writeBufferBytes the part of {@code cacheBytes} that memtables may use before RocksDB
   *     flushes them to disk early
   * @throws IllegalArgumentException if a size is not positive, or memtables would get more than
   *     the whole budget
   */
  public static FrontierStores create(Path root, long cacheBytes, long writeBufferBytes)
      throws IOException {
    if (cacheBytes <= 0 || writeBufferBytes <= 0 || writeBufferBytes > cacheBytes) {
      throw new IllegalArgumentException(
          "Need 0 < writeBufferBytes <= cacheBytes, got %d and %d"
              .formatted(writeBufferBytes, cacheBytes));
    }
    Files.createDirectories(root);
    var cache = new LRUCache(cacheBytes);
    return new FrontierStores(root, cache, new WriteBufferManager(writeBufferBytes, cache));
  }

  /**
   * Opens a partition's store, creating it if needed.
   *
   * @throws IllegalStateException if this partition is already open here
   * @throws RocksDBException if RocksDB cannot open it, for example because another process has it
   */
  public PartitionStore open(int partition) throws RocksDBException {
    checkPartition(partition);
    if (stores.containsKey(partition)) {
      throw new IllegalStateException("Partition %d is already open".formatted(partition));
    }
    var store = PartitionStore.open(folder(partition), cache, writeBufferManager);
    stores.put(partition, store);
    return store;
  }

  /**
   * Returns an open partition's store.
   *
   * @throws IllegalStateException if the partition is not open here
   */
  public PartitionStore get(int partition) {
    checkPartition(partition);
    var store = stores.get(partition);
    if (store == null) {
      throw new IllegalStateException("Partition %d is not open".formatted(partition));
    }
    return store;
  }

  /** Closes a partition's store and keeps its folder, for a fast reopen. Does nothing if closed. */
  public void close(int partition) {
    checkPartition(partition);
    var store = stores.remove(partition);
    if (store != null) {
      store.close();
    }
  }

  /**
   * Closes a partition's store if open, then deletes its folder if present, for a partition that
   * has moved to another node.
   */
  public void delete(int partition) throws IOException {
    close(partition);
    var folder = folder(partition);
    if (Files.notExists(folder)) {
      return;
    }
    List<Path> paths;
    try (var walk = Files.walk(folder)) {
      paths = walk.sorted(Comparator.reverseOrder()).toList(); // files before their folders
    }
    for (var path : paths) {
      Files.delete(path);
    }
  }

  /** Total memory the cache holds now, memtables included. */
  long memoryUsage() {
    return cache.getUsage();
  }

  /** Closes every store, then the shared memory they use. */
  @Override
  public void close() {
    List.copyOf(stores.keySet()).forEach(this::close);
    writeBufferManager.close();
    cache.close();
  }

  private Path folder(int partition) {
    return root.resolve("partition-%d".formatted(partition));
  }

  private static void checkPartition(int partition) {
    if (partition < 0) {
      throw new IllegalArgumentException("Partition must not be negative: %d".formatted(partition));
    }
  }
}
