# Project Checkpoint

Current state of the project, for anyone picking up this work. Update it at the end of every chunk of work.

Last updated: 2026-11-22

## Sources of truth

- Design: https://app.notion.com/p/412b6fbf8e098183964ff6bd4eb4444b
- Sprint board: https://app.notion.com/p/a64b93defece49889dbeb95fff3029cb
- Repo: https://github.com/chowdharyniraj97/personalwebcrawler (public)

Read a ticket's acceptance criteria in Notion before starting it.

## Goal

Learn concurrency, parallelism and production operations by building a domain-partitioned crawler. The design targets 1B URLs. The actual target is 10 nodes and 10M URLs, developed free on an M3 Mac with 16 GB and about 27 GB of free disk.

## How we work

- Claude writes the code. The user reads it and approves each chunk once they understand it.
- Work in reasonably sized chunks, explain them, then stop and wait. Do not start the next chunk unasked.
- Run tests and lint once at the end of a chunk that changes code, not after every small step.
- Commit, push, merge, or edit Notion only when the user asks.
- Use a feature branch and merge to `main` through a pull request.
- Use Lombok `@UtilityClass` for static helpers. Follow `.claude/CLAUDE.md`.

## Decisions

| Decision | Why |
| --- | --- |
| Java 25 | First LTS where `synchronized` no longer pins virtual threads |
| Gradle, version catalog in `gradle/libs.versions.toml` | Versions shared across modules |
| Plain consumer with our own changelog, not Kafka Streams | Streams would hide what the project is meant to teach |
| One instance of everything first, then scale | Saves memory; a node joins a consumer group from day one, so scaling needs no new code |
| 64 partitions | Partition count is the one setting that is hard to change later |
| Topics created by `infra/kafka/create-topics.sh` in a one-shot Compose service, `kafka-init` | Kafka has no config file that declares topics, and its own CLI needs no Java from us |
| Protobuf `DiscoveredUrl`, zstd on the producer | Four fields, safe schema evolution; batch compression is what makes URLs small |
| Key is the registered domain, ICANN suffixes only | One node owns a whole company's traffic for politeness; `*.github.io` is one domain |
| Normalizer keeps URLs distinct when unsure | A duplicate fetch is acceptable, a lost URL is not |
| One RocksDB database per partition, with column families `records` and later `due` (CRW-9) | Moving a partition is closing and deleting one folder. Column families allow atomic batches across a record and its index entry |
| The URL is a field of `FrontierRecord`, not a separate map | The design's fingerprint cannot be turned back into a URL. The compacted changelog keeps only the latest message per key, so each message must be complete anyway. One map means one lookup, no cross-map batch, and identical RocksDB and changelog values. The cost is larger RocksDB rewrites, fine at 10M URLs |
| `FrontierRecord` is a Protobuf message, with the raw ETag as a string | RocksDB only stores bytes. Protobuf was already in the project, needs no hand-written codec, writes nothing for empty fields, and allows adding fields later. `If-None-Match` needs the exact ETag, so it cannot be a hash |
| All partition stores share one block cache (256 MB planned) and one `WriteBufferManager` (128 MB planned) that counts memtables inside that cache | 64 databases with default settings would use several GB. One shared cache caps all of RocksDB's memory with one number, as the RocksDB wiki recommends for several databases in one process. Sizes are passed in by the caller, not hardcoded |
| No leases: an in-memory in-flight set per partition instead (`ConcurrentHashMap.newKeySet()`), claimed when the scheduler queues a URL and released when its fetch finishes or it is dropped from a queue | Partition ownership already means one node per URL, so "being fetched" can live in memory. A crash loses the set, and the records still say SCHEDULED and due, so the next scan retries them. Removes the lease fields, the LEASED state, the expiry sweeper and per-key locking, and cuts writes per crawl from 3 to 1. Replaces CRW-12 as written in Notion |
| Changelog records go to the same partition number as their input record (CRW-10) | The default partitioner would scatter one partition's state across all changelog partitions |
| Wait for the changelog write to be confirmed before writing RocksDB (CRW-10) | Otherwise a crash between the two leaves a URL only on local disk, and a replay skips the changelog |
| Virtual threads for fetching, fixed pool for parsing, platform threads for RocksDB | JNI calls pin virtual threads |
| Dev free on the Mac; short AWS runs later, Hetzner as fallback | AWS credits are a one-time pot |

## Ticket status

Done: CRW-2 to CRW-7. In progress: CRW-1, CRW-8. Everything else has not started.

- **CRW-1:** still needs the `frontier` and `fetcher` modules and pins for RocksDB and Caffeine.
- **CRW-5:** partitioning by domain key is proven: 1,000 URLs across 100 domains were loaded and read back, and each domain stayed on one partition. The ticket is marked Done.

Planned order. Sprint 1: 2, 3, 7. Sprint 2: 8, 9, 12, 10, 11, 13, plus 27 (metrics). Sprint 3: 15, 14, 16, 17, 18, 19, 20. Sprint 4: 21, 22, 24, 23, 25, 26. Sprint 5: 29, scale-up to 3 brokers and 10 nodes, 28, 31, 30, 32.

**The Notion tickets are out of date, and the user chose not to edit them.** Follow the decisions above instead where they differ: CRW-1 says Java 21, CRW-2 says 3 brokers, CRW-3 says 1024 partitions and RF=3, CRW-27 is listed in Sprint 5, CRW-30 describes a StatefulSet with local SSD, and no scale-up ticket exists.

## Current state

`main` is at `cb1f4e1`. It contains `url-core` (`UrlNormalizer`, `RegisteredDomain`, `Fingerprint`, the `DiscoveredUrl` Protobuf schema), the local stack in `compose.yaml` with `kafka-init` creating topics, `synthetic-web`, a placeholder `node`, and `seed-loader`.

CRW-2 is complete and merged to `main` through PR #2. It added `compose.yaml`, `node/`, `synthetic-web/`, `infra/unbound/`, `.dockerignore` and `README.md`. The Notion ticket is marked Done.

Verified: `./gradlew check` passes with 165 tests. All three containers are healthy. The synthetic web serves pages and 429s, `dig` resolves `*.test` from the Mac, and `./gradlew :node:run` logs `Connected to 1 broker(s)`.

Environment: Colima, Docker and Compose are installed. Colima was last left running with 2 CPUs and 4 GB, with the stack up. Start it with `colima start --cpu 2 --memory 4`, and free the memory with `docker compose down` and `colima stop`. Gradle downloads JDK 25 itself; the system JDK is 17.

## Next steps

1. CRW-8 chunks (a) and (b) are committed locally on the branch `frontier-store`, not pushed. Chunk (a): the `frontier` module with the Protobuf `FrontierRecord` message (`frontier/src/main/proto/crawler/v1/frontier_record.proto`), `FrontierRecords.scheduled`, tests, and the RocksDB 11.8.1 pin in the version catalog. The same branch also fixes the seed loader to leave `DiscoveredUrl.source` empty, as the schema says for seeds.
2. CRW-8 chunk (b): `FrontierRecord` lost its lease fields and LEASED state and was renumbered 1 to 7 with no gaps, since no data existed yet. `PartitionStore` uses plain RocksDB (no `TransactionDB`, no Bloom filter, which the user declined for now) with `records` and `due` column families. The API is batch-first and has no lock: `findAbsent(fingerprints)` with one `multiGet`, then `insertAll(records)` writing records and due entries in one `WriteBatch`, so CRW-10 can confirm the changelog in between. Also `get` and `close`. One ingest thread per partition is the documented rule. `./gradlew check` passed with 207 tests.
3. CRW-8 chunk (c), written and not yet committed: `FrontierStores.create(root, cacheBytes, writeBufferBytes)` makes one `LRUCache` and one `WriteBufferManager` charged to it, and stores in `root/partition-<n>/`. Methods: `open(n)`, `get(n)`, `close(n)` (keeps the folder), `delete(n)` (closes, then removes the folder) and `close()`. `PartitionStore` gained a package-private `open(Path, Cache, WriteBufferManager)`; `open(Path)` still uses RocksDB defaults. No lock: `open`, `close(n)` and `delete` come from the consumer thread, and the caller stops a partition's workers before closing or deleting it. `./gradlew check` passes with 226 tests. The user has not reviewed it yet. Once they approve, CRW-8 is complete except the partition-count check from Known limits, which belongs with the consumer in CRW-10.
4. Then CRW-9 (due index), CRW-10 (ingest consumer), CRW-11 (restore), CRW-13 (scheduler, host queues and the in-flight set, absorbing CRW-12) and CRW-27 (metrics).

CRW-3 and CRW-7 were merged to `main` directly, without a PR, at the user's request, each after CI passed on its branch. The local `incoming` topic holds 1,000 test URLs from verifying CRW-7 until `docker compose down -v`.

## AWS, for later

Free Plan with $100 credits, up to $200 with activities. It ends 6 months after account creation or when credits run out. Never join an AWS Organization or Control Tower, because that ends the credits. Allowed EC2 types: `t3.micro`, `t3.small`, `t4g.micro`, `t4g.small`, `c7i-flex.large`, `m7i-flex.large`. The full cluster needs 28 vCPUs and the default quota may be 5, so a quota increase to 32 in `us-east-2` is needed. The user's actual quota and any request are unconfirmed.

## Known limits and traps

- Colima's SSH port forwarding cannot carry UDP, and UDP 5353 on a Mac is multicast DNS. From the Mac, reach `unbound` with `dig +tcp -p 15353`.
- Spotless must target `src/*/java/**`, or it fails on generated Protobuf code in CI.
- In the version catalog, a name like `protobuf-plugin` turns `protobuf` into a group. Keep version names from sharing a prefix.
- `EffectiveTldFinder`'s third argument means exclude private suffixes.
- Hosts under a suffix missing from the list are not shortened, so `www.site1.test` stays as is.
- The normalizer rejects raw `|`, `<` and `[` in paths. Fix in CRW-21.
- `java.net.IDN` uses the older standard, so `faß.de` becomes `fass.de`.
- The bundled Public Suffix List is from December 2025, and nothing calls `RegisteredDomain.reload` yet.
- The synthetic web lacks HTTPS, redirects, compression, `ETag`/304, large or slow bodies and dropped connections. Add each with the ticket that needs it, such as `ETag` in CRW-19.
- Retention limits are needed on `incoming` locally because disk is small, and they can drop unread URLs.
- `kafka-topics.sh --if-not-exists` leaves an existing topic unchanged even if its settings differ from the script. A node should fail fast on a wrong partition count when it starts consuming in CRW-8.
- With no reachable broker, the seed loader waits up to 60 seconds per URL (`max.block.ms`) before counting a failure. On a large file, stop it by hand.
- `min.insync.replicas` is 1, the broker default. Set it when moving to 3 brokers.
