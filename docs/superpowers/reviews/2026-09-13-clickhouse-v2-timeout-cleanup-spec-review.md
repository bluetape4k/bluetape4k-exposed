# #875 설계 문서 검토

## 검토 범위와 근거

- 대상: `docs/superpowers/specs/2026-09-13-clickhouse-v2-timeout-cleanup-design.md`
- 기준: `origin/develop` `5f6f2e7a2aa03a10512b8dcd85414673ca462166`
- 소스: `ClickHouseQueryStreaming.kt`, `ClickHouseExtensions.kt`, `ClickHouseV2Options.kt`, `ClickHouseV2PropertyMapping.kt`, `ClickHouseQueryLifecycleTest.kt`, 기존 #860/#863/#864 증적
- 검토 방식: 현재 런타임에서 독립 lane dispatch를 수행할 수 없어 여섯 관점을 주 세션에서 각각 재검토한 inline fallback이다. 따라서 본 검토는 **비독립**이며, 1인 개발자 운영 규칙에 따라 human-review lane은 N/A로 기록한다.

## 관점별 결과

| 우선순위 | 관점 | 근거와 판정 | 필요한 조치 |
|---|---|---|---|
| N/A | 성능 | timeout matrix와 bounded polling을 요구하고 무제한 재시도·buffer를 허용하지 않는다. | plan에 polling deadline과 repeat count를 명시한다. |
| N/A | 안정성 | `CancellationException` 재전파, `NonCancellable` producer join, local resource 0, pool 재사용과 cleanup 오류 우선순위가 명시되어 있다. | 구현에서 broad catch가 cancellation을 삼키지 않는지 검증한다. |
| P2 | 보안 | query_id와 session state를 관찰하지만 로그·receipt에 credential이 섞이지 않는다는 명시가 필요하다. | plan에 redaction assertion과 고정된 non-secret query_id 규칙을 추가한다. |
| P2 | 운영 | `system.processes`/query log 권한·버전 차이를 N/A로 기록하도록 했으나 bounded polling receipt 경로를 구체화해야 한다. | plan에 정확한 observation artifact 경로와 N/A 판정 형식을 추가한다. |
| N/A | 개발/API | public `queryFlow` signature와 Exposed transaction ownership을 유지하며 generic abort API를 거부한다. | 구현에서 최소 수정 원칙과 API diff 검사를 유지한다. |
| P2 | 사용자/호출자 | timeout 종류와 caller finite timeout 책임을 분리해 README에 반영하도록 했다. | plan에 EN/KO 문서의 동일한 예외·N/A wording 검증을 추가한다. |

## 통합 판정

- P0: 0
- P1: 0
- P2: 3 (redaction assertion, observation artifact 경로, locale wording 검증; plan 단계에서 구체화)
- P3: 0

P2는 설계 진행을 막지 않지만, timeout/cancel 증적이 보안 정보나 성공 보장으로 오해되지 않도록 plan에 구체적인 artifact/검증 명령을 넣어야 한다. 원격 종료와 V2 socket timeout을 입증하지 못하면 N/A로 남긴다는 결정은 기존 #863 증거와 일치한다.

## SPW-01~05 통합 review gate

- **SPW-01 PASS**: 대상 독자, spec 경로, 기준 SHA, source ledger와 미입증 상태를 기록했다.
- **SPW-02 PASS**: 문제·대안·lifecycle/오류 계약·호환성·실패 모드·수용 기준을 모두 검토했다.
- **SPW-03 PASS**: 한국어 기술 register와 `요청 수락`/`원격 종료`/`정리` 용어, driver key 보존을 확인했다.
- **SPW-04 PASS**: streaming/options/tests의 실제 경계와 primary/suppressed 정책을 source와 대조했다.
- **SPW-05 PASS**: Markdown 표·코드 블록·체크리스트를 재독했고 P2 조치를 plan으로 이관했다.

**Step 2-R verdict: PASS (P0=0, P1=0).**

