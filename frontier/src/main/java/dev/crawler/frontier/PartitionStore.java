package dev.crawler.frontier;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.crawler.frontier.proto.FrontierRecord;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.Cache;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteBufferManager;
import org.rocksdb.WriteOptions;

/**
 * The frontier of one Kafka partition: one RocksDB database in its own folder, with two column
 * families.
 *
 * <ul>
 *   <li>{@code records}: fingerprint → {@link FrontierRecord}. The table.
 *   <li>{@code due}: next due time + fingerprint → nothing. An index, so the scheduler can read
 *       URLs in the order they fall due.
 * </ul>
 *
 * <p>Ingest works in batches and in two steps, so the changelog can be confirmed in between: {@link
 * #findAbsent} says which URLs are new, the caller sends those to the changelog and waits, then
 * {@link #insertAll} writes them. A record and its index entry are written in one {@link
 * WriteBatch}, so after a crash a record never lacks its index entry.
 *
 * <p><b>Threading:</b> {@link #findAbsent} and {@link #insertAll} must be called by one thread per
 * partition, the partition's ingest thread, so nothing can insert between the two calls. There is
 * no lock. {@link #get} is safe from any thread. Use platform threads: RocksDB is native code, and
 * a virtual thread inside a native call stays pinned to its carrier thread.
 */
public final class PartitionStore implements AutoCloseable {
  private static final byte[] RECORDS = "records".getBytes(StandardCharsets.UTF_8);
  private static final byte[] DUE = "due".getBytes(StandardCharsets.UTF_8);
  private static final byte[] EMPTY = new byte[0];

  static {
    RocksDB.loadLibrary();
  }

  private final RocksDB db;
  private final List<ColumnFamilyHandle> handles;
  private final ColumnFamilyHandle records;
  private final ColumnFamilyHandle due;
  private final DBOptions dbOptions;
  private final ColumnFamilyOptions familyOptions;
  private final WriteOptions writeOptions = new WriteOptions();

  private PartitionStore(
      RocksDB db,
      List<ColumnFamilyHandle> handles,
      DBOptions dbOptions,
      ColumnFamilyOptions familyOptions) {
    this.db = db;
    this.handles = List.copyOf(handles);
    this.records = handles.get(1);
    this.due = handles.get(2);
    this.dbOptions = dbOptions;
    this.familyOptions = familyOptions;
  }

  /**
   * Opens the store in {@code directory}, creating it if needed, with RocksDB's default memory
   * settings: its own block cache and memtables. The parent directory must exist.
   *
   * @throws RocksDBException if the store cannot be opened, for example because it is already open
   */
  public static PartitionStore open(Path directory) throws RocksDBException {
    return open(directory, new DBOptions(), new ColumnFamilyOptions());
  }

  /**
   * Opens the store like {@link #open(Path)}, but using memory shared with other stores: reads are
   * cached in {@code cache}, and {@code writeBufferManager} caps the memtables of every store that
   * shares it. Both must stay open until every store using them is closed.
   */
  static PartitionStore open(Path directory, Cache cache, WriteBufferManager writeBufferManager)
      throws RocksDBException {
    return open(
        directory,
        new DBOptions().setWriteBufferManager(writeBufferManager),
        new ColumnFamilyOptions()
            .setTableFormatConfig(new BlockBasedTableConfig().setBlockCache(cache)));
  }

  private static PartitionStore open(
      Path directory, DBOptions dbOptions, ColumnFamilyOptions familyOptions)
      throws RocksDBException {
    dbOptions.setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
    // Every RocksDB database has a "default" family, which must be listed even though it is unused.
    var families =
        List.of(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, familyOptions),
            new ColumnFamilyDescriptor(RECORDS, familyOptions),
            new ColumnFamilyDescriptor(DUE, familyOptions));
    var handles = new ArrayList<ColumnFamilyHandle>();

    try {
      var db = RocksDB.open(dbOptions, directory.toString(), families, handles);
      return new PartitionStore(db, handles, dbOptions, familyOptions);
    } catch (RocksDBException e) {
      familyOptions.close();
      dbOptions.close();
      throw e;
    }
  }

  /**
   * Returns the fingerprints that are not stored yet, looked up in one call. Repeats in the input
   * are checked once.
   */
  public Set<Long> findAbsent(Collection<Long> fingerprints) throws RocksDBException {
    var unique = List.copyOf(new LinkedHashSet<>(fingerprints));
    if (unique.isEmpty()) {
      return Set.of();
    }
    var keys = unique.stream().map(PartitionStore::recordKey).toList();
    var values = db.multiGetAsList(Collections.nCopies(keys.size(), records), keys);

    return IntStream.range(0, unique.size())
        .filter(i -> values.get(i) == null)
        .mapToObj(unique::get)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Writes every record and its due index entry, all or nothing, in one write.
   *
   * <p>Records are written as given, without checking what is stored. Pass only fingerprints that
   * {@link #findAbsent} returned, or records restored into an empty store: overwriting a record
   * with a different due time would leave its old index entry behind.
   *
   * @throws IllegalArgumentException if any record's next due time is negative, which would sort
   *     after every positive time in the index; nothing is written
   */
  public void insertAll(Map<Long, FrontierRecord> recordsByFingerprint) throws RocksDBException {
    recordsByFingerprint.values().forEach(PartitionStore::validate);
    if (recordsByFingerprint.isEmpty()) {
      return;
    }

    try (var batch = new WriteBatch()) {
      for (var entry : recordsByFingerprint.entrySet()) {
        var fingerprint = entry.getKey();
        var record = entry.getValue();
        batch.put(records, recordKey(fingerprint), record.toByteArray());
        batch.put(due, dueKey(record.getNextDueMs(), fingerprint), EMPTY);
      }
      db.write(writeOptions, batch);
    }
  }

  /**
   * Returns the record for a fingerprint, if present.
   *
   * @throws IllegalStateException if the stored bytes are not a valid record
   */
  public Optional<FrontierRecord> get(long fingerprint) throws RocksDBException {
    var bytes = db.get(records, recordKey(fingerprint));
    if (bytes == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(FrontierRecord.parseFrom(bytes));
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException(
          "Corrupt record for fingerprint %016x".formatted(fingerprint), e);
    }
  }

  /**
   * Whether the due index has an entry for this fingerprint at this time. For tests until CRW-9.
   */
  boolean isIndexedAsDue(long fingerprint, long nextDueMs) throws RocksDBException {
    return db.get(due, dueKey(nextDueMs, fingerprint)) != null;
  }

  /**
   * Closes the database and frees its native memory. Everything written is already in the
   * write-ahead log. Column family handles close before the database, and options after it.
   */
  @Override
  public void close() {
    handles.forEach(ColumnFamilyHandle::close);
    db.close();
    writeOptions.close();
    familyOptions.close();
    dbOptions.close();
  }

  private static void validate(FrontierRecord record) {
    Objects.requireNonNull(record, "record");
    if (record.getNextDueMs() < 0) {
      throw new IllegalArgumentException(
          "nextDueMs must not be negative: %d".formatted(record.getNextDueMs()));
    }
  }

  private static byte[] recordKey(long fingerprint) {
    return ByteBuffer.allocate(Long.BYTES).putLong(fingerprint).array();
  }

  /**
   * Time first, so keys sort by due time; the fingerprint makes keys unique and points back to the
   * record. Big-endian numbers sort like the numbers themselves, as long as they are not negative.
   */
  private static byte[] dueKey(long nextDueMs, long fingerprint) {
    return ByteBuffer.allocate(2 * Long.BYTES).putLong(nextDueMs).putLong(fingerprint).array();
  }
}
