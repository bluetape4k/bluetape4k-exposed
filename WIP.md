# 진행 중인 작업 - bluetape4k-exposed

스냅숏: 2026-08-16 KST
범위: `1.13.0` 개발선의 Spring Data Exposed repository 개선.

## 릴리스 목표

Epic [#658](https://github.com/bluetape4k/bluetape4k-exposed/issues/658)의 child를
stacked PR train으로 전달합니다. 현재 slot은 JDBC repository의 Spring Data
`FluentQuery` projection과 QBE terminal을 실제 SQL 실행 경로에 연결하는
[#642](https://github.com/bluetape4k/bluetape4k-exposed/issues/642)입니다.

## 검증 기준

- Attached persisted DAO probe만 QBE 입력으로 허용합니다.
- Closed interface, Kotlin data class, Java record projection의 selected column을 SQL로
  pushdown합니다.
- sort, limit, page, count, exists, cardinality와 literal LIKE escape를 H2, PostgreSQL,
  MySQL V8에서 검증합니다.
- Cursor-backed `stream()`은 caller-owned transaction, 같은 thread, 명시적 close를
  요구하며 upfront materialization을 허용하지 않습니다.
- 기존 public constructor와 repository API/ABI를 유지합니다.
- 아직 `1.13.0`을 배포하지 않았으므로 안정판 `docs/manual/**`는 변경하지 않습니다.

## 현재 상태

- Issue #642의 설계와 구현 계획은 독립 검토에서 P0=0/P1=0으로 확정했습니다.
- JDBC QBE compiler, projection mapper, SQL terminal executor, direct JDBC cursor lease를
  TDD로 구현하고 대표 dialect 회귀 검증을 진행하고 있습니다.
- 문서·정적 분석·API/ABI·module 검증과 독립 pre-PR review가 끝난 뒤 PR을 생성합니다.
