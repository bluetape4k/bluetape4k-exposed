# Issue 126 Redisson `upsertAll` 계획

## 분류

Type B Fast Track: 기존 Redisson 저장소 API를 확장하고 테스트와 README를 갱신한다.

## 단계

1. Redisson JDBC, Suspended JDBC, R2DBC 저장소 인터페이스에
   `DEFAULT_UPSERT_BATCH_SIZE = 100`과 `upsertAll`을 추가한다.
2. writer 기반 cache map을 소유한 abstract Redisson 저장소에서 `upsertAll`을 재정의한다.
3. 기존 `putAll` 메서드는 `upsertAll`에 위임한다.
4. `findAll` cache warming도 `upsertAll`을 사용한다.
5. bulk update와 신규 record 동작을 검증하는 write-through scenario test를 추가한다.
6. JDBC 및 R2DBC Redisson module의 English/Korean README를 갱신한다.
7. 대상 compile과 Redis Testcontainers 기반 scenario test로 검증한다.

## 검토 제약

local subscription이 낮아져 Claude Code review를 사용할 수 없다. 현재 세션
자체가 Codex이므로 외부 Codex CLI review도 의도적으로 생략한다. local diff
review와 Gradle 검증을 review 근거로 사용한다.
