---
title: "Choosing a Cache Backend"
locale: "en"
releaseRef: "1.12.1"
---

# Choosing a Cache Backend

The first decision is not the Redis client. Decide who must observe the same cached value, how long stale data is acceptable, and when a successful write must already be durable in the database.

![Cache backend and write-policy decision map](../../assets/cache/cache-selection.png)

## Start with the sharing boundary

| Need | Start with | Why | Main cost |
| --- | --- | --- | --- |
| One application process | Caffeine | No network or external service | Every JVM owns different state |
| Shared state across instances | Lettuce or Redisson remote cache | Redis is the common state owner | Network and Redis become part of availability |
| Hot local reads plus shared Redis | Near Cache | L1 avoids some round trips | Two tiers must invalidate and expire coherently |

Choose JDBC or R2DBC independently from the cache backend. JDBC adapters provide blocking paths, with suspended JDBC variants where available. R2DBC adapters keep repository operations suspend; that changes cancellation and transaction ownership, not the cache consistency model.

## Then choose the write contract

| Pattern | Read miss | Write owner | Return means | Use when |
| --- | --- | --- | --- | --- |
| Cache-aside | Application loads DB and caches | Application transaction | DB commit completed; invalidation may still fail | Existing service owns writes |
| Read-through | Loader reads DB | Cache loader | Miss load completed | Repository should centralize miss handling |
| Write-through | Configured writer updates DB | Cache map writer | DB write completed or failed | Synchronous durability is required |
| Write-behind | Loader still handles reads | Bounded async writer | Cache accepted the write; DB may not be committed | Delayed durability and loss window are acceptable |
| Invalidation | No load | Cache only by default | Selected cache state was removed | DB remains the authority |

An ordinary `put` is not proof of write-through. Check `CacheWriteMode`, `LettuceCacheConfig`/`RedissonCacheConfig`, and whether the concrete map has a database writer.

## Backend comparison

| Concern | Caffeine | Lettuce | Redisson |
| --- | --- | --- | --- |
| Location | In-process | Redis; selected paths add Caffeine near cache | Redis `RMap`/`RLocalCachedMap` |
| Key/value contract | Prefix plus local ID/value | Explicit `RedisCodec<String, E>`; TTL applied on set | Configured Redisson codec; unsafe binary families require trust opt-in |
| Client lifecycle | Repository owns local cache/scope | Application owns `RedisClient`; repository closes connection | Application owns `RedissonClient` |
| Partial failure | Queue saturation or process loss | Redis timeout/retry, dead letter, DB failure | Redis future, writer/DB failure, optional DB deletion on invalidation |
| Isolation | Unique prefix per test | Isolated Redis and prefix | Isolated Redis and map name |

Lettuce is a good fit when the application wants explicit codecs and connection control. Redisson is a good fit when its loaded-map and local-cached-map policy is useful. That is an ownership decision, not a universal performance ranking.

## TTL, invalidation, and stale data

Set TTL from the business staleness budget. In a local cache, another instance cannot see invalidation. In a near cache, both L1 and Redis must be cleared or synchronized. For Redisson, `deleteFromDBOnInvalidate=false` is the safe default: turning it on changes a cache operation into database deletion.

Key prefixes, cache names, codecs, and entity schemas are stored-data contracts. Roll out a migration or versioned namespace when any of them changes. Do not silently reinterpret old Redis bytes with a new codec.

## Failure and shutdown checklist

- Decide whether a cache backend outage fails the request, bypasses cache, or allows stale local data.
- Bound retries, timeouts, write-behind queues, and shutdown drain time.
- Observe queue depth, rejected writes, dead letters, circuit state, and invalidation lag.
- Close repositories before application-owned Redis clients.
- Test cancellation around R2DBC and suspended JDBC paths.
- Use unique namespaces and clear them in test teardown.

## Recommended learning path

Begin with [Exposed cache foundation](../modules/bluetape4k-exposed-cache.md). Implement one backend in `READ_ONLY`/read-through mode, prove cache-only invalidation, then add the write policy. Near cache and write-behind should be the last steps because they add a second consistency boundary or a delayed durability window.

## Sources

- [`CacheMode`](../../../../exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/CacheMode.kt)
- [`CacheWriteMode`](../../../../exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/CacheWriteMode.kt)
- [`LocalCacheConfig`](../../../../exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/LocalCacheConfig.kt)
- [JDBC Lettuce repository](../../../../exposed/jdbc-lettuce/src/main/kotlin/io/bluetape4k/exposed/lettuce/repository/AbstractJdbcLettuceRepository.kt)
- [R2DBC Redisson repository](../../../../exposed/r2dbc-redisson/src/main/kotlin/io/bluetape4k/exposed/r2dbc/redisson/repository/AbstractR2dbcRedissonRepository.kt)
