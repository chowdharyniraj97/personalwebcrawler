# Personal Web Crawler

A distributed web crawler built to learn concurrency, parallelism and production operations. Each node owns a set of Kafka partitions and handles dedup, scheduling, politeness and fetching for those domains.

The design targets 1B URLs. This project proves it at 10 nodes and 10M URLs, starting with one instance of everything.

## Prerequisites

- Docker with Compose. On macOS: `brew install colima docker docker-compose`, then `colima start --cpu 2 --memory 4`.
- A JDK 17 or newer to start Gradle. Gradle downloads JDK 25 for the build itself.

## Build and test

```sh
./gradlew check          # compile, unit tests and formatting check
./gradlew spotlessApply  # fix formatting
```

## Local stack

```sh
docker compose up -d --build
```

| Service | Purpose | From your machine |
| --- | --- | --- |
| `kafka` | Single KRaft broker | `localhost:19092` |
| `unbound` | Caching DNS that resolves every `*.test` domain to the synthetic web | `localhost:15353` (TCP) |
| `synthetic-web` | Fake internet to crawl | `localhost:8080` |

To test DNS from your machine:

```sh
dig +tcp @127.0.0.1 -p 15353 site77.test +short
```

Stop it with `docker compose down`. Add `-v` to delete the Kafka data as well.

## Run a node against the stack

```sh
./gradlew :node:run
```

The node is a placeholder for now: it connects to Kafka and logs the broker count. Set `CRAWLER_BOOTSTRAP_SERVERS` to point it at a different cluster.

## Synthetic web

`synthetic-web` generates every page from a seed, so it can serve millions of URLs without storing them. The defaults are 1,000 domains with 10,000 pages each, or 10M URLs. Domains are named `site0.test`, `site1.test` and so on.

```sh
curl -H 'Host: site1.test' localhost:8080/p/9
```

| Domain index | Behavior |
| --- | --- |
| All | `robots.txt` disallows `/private/` |
| `% 5 == 0` | `Crawl-delay: 2` |
| `% 10 == 3` | Every fifth page request returns 429 with `Retry-After` |
| `% 20 == 7` | Contains an endless `/trap/a/a/...` URL trap |

Configure it with `SYNTHETIC_DOMAINS`, `SYNTHETIC_PAGES_PER_DOMAIN`, `SYNTHETIC_LINKS_PER_PAGE`, `SYNTHETIC_SEED` and `SYNTHETIC_MAX_LATENCY_MS`.
