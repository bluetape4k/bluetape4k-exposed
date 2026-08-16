# 진행 중인 작업 - bluetape4k-exposed

스냅숏: 2026-08-17 KST
범위: `1.13.0` 개발선의 Spring Data Exposed repository 개선.

## 릴리스 목표

Epic [#658](https://github.com/bluetape4k/bluetape4k-exposed/issues/658)의 child를
stacked PR train으로 전달합니다. 현재 slot은 R2DBC repository에 coroutine-native
Query by Example과 immutable `FluentQuery` terminal을 실제 SQL 실행 경로에 연결하는
[#643](https://github.com/bluetape4k/bluetape4k-exposed/issues/643)입니다.

## 검증 기준

- coroutine QBE probe는 repository domain type과 정확히 일치해야 하며, matcher snapshot은
  SQL 실행 전에 detached 상태로 고정합니다.
- Closed interface, Kotlin constructor type, Java record projection의 selected column을
  SQL로 pushdown합니다.
- sort, limit, page, slice, count, exists, strict cardinality와 literal LIKE escape는 H2
  edge-case 통합군에서 검증하고, PostgreSQL/MySQL V8은 별도 parameterized smoke에서
  findOne/count/exists/closed projection/Flow 결과 의미를 확인합니다.
- `Flow`는 cold이며 collect 시점의 coroutine context와 caller-owned outer transaction을
  사용합니다. `useNestedTransactions=true`인 활성 transaction은 SQL 전에 거부합니다.
- 기존 public constructor와 repository API/ABI를 유지합니다.
- 아직 `1.13.0`을 배포하지 않았으므로 안정판 `docs/manual/**`는 변경하지 않습니다.

## 현재 상태

- Issue #643의 설계와 구현 계획은 독립 검토에서 P0=0/P1=0으로 확정했습니다.
- R2DBC QBE compiler, projection mapper, SQL terminal executor, transaction lease를
  TDD로 구현했고 H2 edge-case와 PostgreSQL/MySQL V8 parameterized backend smoke를
  순차 통과했습니다(XML tests>0, failures/errors/skipped=0).
- 문서·정적 분석·API/ABI·module 검증과 독립 pre-PR review를 완료했습니다. PR 생성은
  별도 target/base/head 승인 게이트로 남아 있습니다.
