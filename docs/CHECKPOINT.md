# Project Checkpoint

Current state of the project, for anyone picking up this work. Update it at the end of every chunk of work.

Last updated: 2026-11-09

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
| Protobuf `DiscoveredUrl`, zstd on the producer | Four fields, safe schema evolution; batch compression is what makes URLs small |
| Key is the registered domain, ICANN suffixes only | One node owns a whole company's traffic for politeness; `*.github.io` is one domain |
| Normalizer keeps URLs distinct when unsure | A duplicate fetch is acceptable, a lost URL is not |
| Virtual threads for fetching, fixed pool for parsing, platform threads for RocksDB | JNI calls pin virtual threads |
| Dev free on the Mac; short AWS runs later, Hetzner as fallback | AWS credits are a one-time pot |

## Ticket status

Done: CRW-2, CRW-4, CRW-6. In progress: CRW-1, CRW-5. Everything else has not started.

- **CRW-1:** still needs the `frontier` and `fetcher` modules and pins for RocksDB and Caffeine.
- **CRW-5:** still needs proof that Kafka partitions by the domain key. Verify in CRW-7.

Planned order. Sprint 1: 2, 3, 7. Sprint 2: 8, 9, 12, 10, 11, 13, plus 27 (metrics). Sprint 3: 15, 14, 16, 17, 18, 19, 20. Sprint 4: 21, 22, 24, 23, 25, 26. Sprint 5: 29, scale-up to 3 brokers and 10 nodes, 28, 31, 30, 32.

**The Notion tickets are out of date, and the user chose not to edit them.** Follow the decisions above instead where they differ: CRW-1 says Java 21, CRW-2 says 3 brokers, CRW-3 says 1024 partitions and RF=3, CRW-27 is listed in Sprint 5, CRW-30 describes a StatefulSet with local SSD, and no scale-up ticket exists.

## Current state

`main` is at `b2be813`. It contains `url-core`: `UrlNormalizer`, `RegisteredDomain`, `Fingerprint`, the `DiscoveredUrl` Protobuf schema, and their tests.

CRW-2 is complete and merged to `main` through PR #2. It added `compose.yaml`, `node/`, `synthetic-web/`, `infra/unbound/`, `.dockerignore` and `README.md`. The Notion ticket is marked Done.

Verified: `./gradlew check` passes with 165 tests. All three containers are healthy. The synthetic web serves pages and 429s, `dig` resolves `*.test` from the Mac, and `./gradlew :node:run` logs `Connected to 1 broker(s)`.

Environment: Colima, Docker and Compose are installed. Colima was last left running with 2 CPUs and 4 GB, with the stack up. Start it with `colima start --cpu 2 --memory 4`, and free the memory with `docker compose down` and `colima stop`. Gradle downloads JDK 25 itself; the system JDK is 17.

## Next steps

1. Clean up non-test code to match `.claude/CLAUDE.md`. The user approved this.
2. CRW-3 topics, then CRW-7 seed loader.

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
