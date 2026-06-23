# Lessons Learned - R2DBC Lettuce cache-only invalidate (2026-06-23)

Issue: #286

## Lesson

Repository `invalidate` methods must follow the shared cache contract before the backing cache map contract. In R2DBC Lettuce, calling `cache.delete` looked like cache removal but actually entered the write-through writer delete path and could remove DB rows. Use `evict` / `evictAll` when the repository contract says cache-only invalidation.

## Evidence

- `R2dbcCacheRepository` documents `invalidate` and `invalidateAll` as cache removal with no DB effect.
- `AbstractR2dbcLettuceRepository` used `delete` / `deleteAll`, which could invoke DB writer deletion.
- The regression test failed before the production fix and passed after switching to `evict` / `evictAll`.

## Future Guard

When wrapping cache maps with repository semantics, review method names at both abstraction levels. `delete` can mean persistent delete in a write-through map, while `evict` is the cache-only operation.
