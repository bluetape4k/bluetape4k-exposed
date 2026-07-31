# Issue 121 SaveAll 저장소 API 계획

## 목표

기존 저장소 구현과의 호환성을 깨뜨리지 않으면서 핵심 JDBC 및 R2DBC
저장소 인터페이스에 일괄 `saveAll` API를 추가하여 milestone 1.8.1의
issue #121을 구현한다.

## 단계

1. API 확장 지점을 추가한다.
   - `JdbcRepository`에 `BatchInsertStatement.bindSave(entity: E)`를 추가한다.
   - `table.batchInsert`를 사용하는 `saveAll(entities: Iterable<E>): List<ID>`를 추가한다.
   - `R2dbcRepository`에도 같은 계약을 `suspend saveAll`로 제공한다.

2. 대표 저장소 fixture를 갱신한다.
   - JDBC `EdgeCaseRepository`와 `ActorJdbcRepository`에서 `bindSave`를 재정의한다.
   - R2DBC `ActorR2dbcRepository`에서 `bindSave`를 재정의한다.
   - 감사 기능이 있는 JDBC/R2DBC 테스트 저장소에서도 `bindSave`를 재정의한다.

3. 테스트를 추가한다.
   - JDBC `saveAll`이 100개가 넘는 행을 삽입하고 생성된 ID를 반환하는지 검증한다.
   - 메모리 내 dialect에서 JDBC `saveAll`로 10k개 행을 삽입한다.
   - R2DBC `saveAll`이 100개가 넘는 행을 삽입하고 생성된 ID를 반환하는지 검증한다.
   - 메모리 내 dialect에서 R2DBC `saveAll`로 10k개 행을 삽입한다.
   - 감사 기능이 있는 JDBC/R2DBC `saveAll` 테스트에서 삽입된 행과 감사 기본 필드를 검증한다.

4. 검증한다.
   - 먼저 대상 모듈을 compile한다.
   - 변경된 테스트 클래스의 집중 테스트를 실행한다.
   - container 기반 dialect가 너무 느리거나 사용할 수 없으면 H2 검증을 유지하고 환경 제약을 기록한다.

5. 마무리한다.
   - 현재 Codex 세션에서 로컬 diff를 검토한다.
   - `docs/lessons/2026-05-20-issue-121-save-all.md`를 추가한다.
   - Lore trailer를 포함해 commit한다.
   - 로컬 검증이 DoD 기준에 도달하면 branch를 push하고 `debop`에게 할당한 draft PR을 생성한다.

## 알려진 제약

- 현재 구독에서는 Claude Code를 사용할 수 없다는 사용자 지시에 따라 Claude advisor/review를 생략한다.
- Exposed worktree가 IDE project 목록에 포함되지 않으면 IntelliJ diagnostics를 생략한다.
