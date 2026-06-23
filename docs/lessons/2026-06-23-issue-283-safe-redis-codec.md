# Issue 283 Redis Codec Safety Lessons

Date: 2026-06-23
Issue: #283

## Lesson

Repository cache defaults must not inherit generic Redis utility binary codecs when the Redis data can be influenced by an untrusted writer. The exposed repository layer needs an explicit entity codec or an explicit trusted-data opt-in at the repository boundary.

## Guidance

- Do not rely on `LettuceLoadedMap` or `LettuceSuspendedLoadedMap` defaults for repository entity values; the backing utility currently chooses LZ4/Fory.
- For Lettuce repository modules, make the value codec part of the repository constructor contract and keep tests that prove the default sentinel is rejected.
- For Redisson repository modules, reject known Fory/Kryo/JDK-family codecs by default and require `trustedBinaryCache = true` only for private trusted Redis data.
- Treat codec helper names as insufficient evidence. Inspect encode/decode fallback behavior before calling a codec safe for untrusted Redis payloads.
- Keep examples and test fixtures honest: if they retain a trusted binary codec for compatibility or performance, the opt-in should be visible in constructor calls.
- Document codec expectations in both English and Korean README files whenever repository constructor safety changes.

## Follow-up

If the upstream Redis utility modules expose structural JSON codecs without binary fallback, reconsider Redisson defaults and prefer a type-bound structural codec over a trusted-binary opt-in.
