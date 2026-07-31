# Issue #101 — completionAttempts 컬럼: Nullable → 기본값 0의 Non-Nullable

**날짜**: 2026-05-16
**브랜치**: fix/issue-101
**PR**: (pending)

## 근본 원인

`ExposedEventPublicationTable.completionAttempts`는 `nullable()`로 선언되어 있었다.

```kotlin
val completionAttempts = integer("COMPLETION_ATTEMPTS").nullable()
```

0에서 시작해 증가만 하는 counter는 null이면 안 된다. nullable 선언 때문에 모든 산술식에 `COALESCE` 또는 `?: 0` fallback이 필요했고, 모든 호출 지점에 우발적 복잡성이 생겼다.

## 결정

1. **`default(0)`로 변경** — null domain을 완전히 제거한다.
   ```kotlin
   val completionAttempts = integer("COMPLETION_ATTEMPTS").default(0)
   ```

2. **`markResubmitted`를 한 단계의 atomic 작업으로 변경** — non-nullable 컬럼에서는 증가에 `COALESCE`가 필요 없다.
   ```kotlin
   row[table.completionAttempts] = table.completionAttempts + 1
   ```
   이전의 non-atomic SELECT + UPDATE 패턴을 대체한다. 단일 UPDATE expression은 DB engine이 atomic하게 평가한다.

3. **`toPublication`의 `?: 0` fallback 제거** — 컬럼은 `Int?`가 아닌 `Int`이므로 fallback은 컴파일 시점에 불필요하며 실행될 수도 없다.

4. **`import org.jetbrains.exposed.v1.core.plus` 필요** — `ExpressionWithColumnType<T>`용 Exposed `+` operator는 이 위치에 있으며 자동 import되지 않는다.

## 스키마 마이그레이션 참고

이는 컬럼 type 변경(`NULL`→`NOT NULL DEFAULT 0`)이다. `initialize-schema: true`를 사용하는 test/local 환경에서는 `SchemaUtils.create`가 새 DDL을 자동 처리한다. Flyway나 Liquibase를 사용하는 production 배포에는 마이그레이션이 필요하다.

**중요**: 기존 NULL row가 하나라도 있으면 실패하므로 ALTER 전에 UPDATE를 실행한다.

```sql
-- Step 1: nullable constraint를 제거하기 전에 NULL 값을 채운다
UPDATE EVENT_PUBLICATION
   SET COMPLETION_ATTEMPTS = 0
 WHERE COMPLETION_ATTEMPTS IS NULL;

UPDATE EVENT_PUBLICATION_ARCHIVE
   SET COMPLETION_ATTEMPTS = 0
 WHERE COMPLETION_ATTEMPTS IS NULL;

-- Step 2: nullable을 제거하고 기본값을 설정한다
ALTER TABLE EVENT_PUBLICATION
  ALTER COLUMN COMPLETION_ATTEMPTS SET NOT NULL;
ALTER TABLE EVENT_PUBLICATION
  ALTER COLUMN COMPLETION_ATTEMPTS SET DEFAULT 0;

ALTER TABLE EVENT_PUBLICATION_ARCHIVE
  ALTER COLUMN COMPLETION_ATTEMPTS SET NOT NULL;
ALTER TABLE EVENT_PUBLICATION_ARCHIVE
  ALTER COLUMN COMPLETION_ATTEMPTS SET DEFAULT 0;
```

H2는 ALTER 문법이 다르므로 local dev에서는 `SchemaUtils.createMissingTablesAndColumns()`를 사용하거나 drop/recreate한다.

## 검증

```
./gradlew :exposed-spring-modulith:test
# 12개 테스트 통과(H2, PostgreSQL, MySQL) — 0 failed
```

## 대체 대상

이 수정은 `markResubmitted`를 위해 PR #100(issue #84)에서 도입한 `COALESCE` workaround를 대체한다. 두 PR이 merge되면 COALESCE import와 사용은 이 PR의 직접 산술 expression으로 바뀐다.
