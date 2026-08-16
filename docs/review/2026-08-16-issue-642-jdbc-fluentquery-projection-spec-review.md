# 이슈 #642 JDBC `FluentQuery` projection 설계 검토

## 검토 대상

- 설계: `docs/superpowers/specs/2026-08-16-issue-642-jdbc-fluentquery-projection-design.md`
- 기준 branch: `origin/develop`
- 기준 commit: `f8a480537dc9677facdeab78c3b2e413089c0647`
- 범위: 성능, 보안, 개발자/API, 안정성, 운영성, 사용자/repository caller

## 최초 판정

최초 독립 검토에서는 P0는 없었고 다음 P1 범주가 확인되었다.

1. Entity terminal, count, exists, stream의 SQL pushdown 및 resource lifetime.
2. callback-scoped plan escape와 transaction/thread identity.
3. `as`/`project` exact-set, sort append/option, limit/cardinality 의미.
4. QBE compiler 공유, attached DAO probe, LIKE escaping, matcher 검증 순서.
5. custom `searchQuery` join/group/distinct의 row/count cardinality.
6. interface/DTO eager mapping, null/type conversion, exception redaction.
7. one-argument constructor JVM ABI와 factory/direct transaction 차이.
8. EN/KO README와 KDoc의 caller workflow 및 stream 오용 방지.

## 반영한 결정

- immutable plan은 원본 `Example`, explicit property snapshot, accumulated sort,
  limit, scope token, owner thread, transaction identity를 보관한다.
- 모든 QBE terminal은 단일 `ExamplePredicateCompiler`를 사용한다. 같은
  transaction에서 이미 조회된 persisted DAO만 probe로 허용하고 ID는 제외한다.
- projection의 explicit non-empty property set은 required input과 정확히 같아야
  하며 Entity partial projection은 거부한다.
- custom `searchQuery`는 root-table filter-only shape만 허용한다.
- `stream()`은 `Query.iterator()`를 우회하고 public
  `JdbcTransaction.execQuery`에서 JDBC `ResultSet`/statement lease를 직접 소유해
  upfront materialization을 제거한다.
- `ReturnedType`은 input property 확인에만 사용하고 constructor는
  `PreferredConstructorDiscoverer`로 탐색한다.
- 기존 public one-argument constructor descriptor를 유지하고 factory 전용
  생성 경로는 public overload로 노출하지 않는다.
- exception taxonomy와 민감 값/SQL redaction, cursor close, multi-DB 검증 범위를
  수용 기준에 포함했다.

## 재검토

수정본은 두 독립 검토 lane에서 현행 repository 코드, Spring Data Commons 4.1.0,
Exposed 1.4.0 source와 대조했다.

- 종합 closure review: P0=0, P1=0, PASS
- contract/feasibility verification: P0=0, P1=0, PASS

검증 중 발견된 P2 두 건도 설계에 반영했다.

- 서두의 `ReturnedType` 설명을 공개 API와 일치시켰다.
- `supportsMultipleResultSets=false`에서 열린 cursor 소비 중 중첩 SQL을 지원하지
  않는다고 명시하고 회귀 테스트를 추가했다.

## 최종 판정

**PASS — P0=0, P1=0. 구현 계획 작성 가능.**

## DoD Status

- [x] 6개 관점 독립 검토
- [x] 최초 P0/P1 분류
- [x] 모든 P1 설계 반영
- [x] 수정본 독립 재검토
- [x] P0=0/P1=0 최종 판정
- [ ] 구현 계획 작성 및 독립 검토
- [ ] 구현 및 검증

상태: `PENDING` — 설계 gate는 완료했으며 구현 계획 gate가 남아 있다.
