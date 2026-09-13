# #874 설계 문서 검토

## 검토 범위와 근거

- 대상: `docs/superpowers/specs/2026-09-13-clickhouse-v2-rowbinary-e2e-design.md`
- 기준: `origin/develop` `5f6f2e7a2aa03a10512b8dcd85414673ca462166`
- 소스: `ClickHouseRowBinaryExecutor.kt`, `ClickHouseRowBinaryPreflight.kt`, `ClickHouseRowBinaryIntegrationTest.kt`, `ClickHouseRowBinaryBenchmarkTest.kt`, 기존 #867/#871/#863 증적
- 검토 방식: 현재 런타임에서 독립 lane dispatch를 수행할 수 없어 여섯 관점을 주 세션에서 각각 재검토한 inline fallback이다. 따라서 본 검토는 **비독립**이며, 1인 개발자 운영 규칙에 따라 human-review lane은 N/A로 기록한다.

## 관점별 결과

| 우선순위 | 관점 | 근거와 판정 | 필요한 조치 |
|---|---|---|---|
| N/A | 성능 | 실제 wire benchmark를 조건부로만 허용하고 fixture `container=not-run`을 분리한다. 무제한 buffer/throughput 주장을 요구하지 않는다. | 계획에서 측정이 없을 때 fixture-only N/A receipt를 남긴다. |
| N/A | 안정성 | before-first-byte만 fallback, first-byte 이후 no-replay, close exactly-once, pool 재사용과 Testcontainers 순차 실행이 명시되어 있다. | 구현 시 각 경계의 실패 테스트를 유지한다. |
| N/A | 보안 | 테스트 table과 입력은 고정된 상수이며 credential·header·raw property를 추가하지 않는다. | 실제 테스트에서도 동적 식별자를 고정/검증한다. |
| P2 | 운영 | driver/server/image/source SHA와 selector 기록은 명시되어 있으나 실행 receipt의 저장 위치가 계획에서 구체화되어야 한다. | plan에 raw JSON 또는 검증 문서의 정확한 경로와 생성 명령을 추가한다. |
| N/A | 개발/API | public signature 불변, 기존 provider 소유권 유지, `ClickHouseRowBinaryResult`의 실제 `updateCounts`·`acceptedCount`·`acceptedCountMayBeIncomplete` 필드를 사용한다. | 구현에서 새 API를 만들지 않는다. |
| P2 | 사용자/호출자 | README EN/KO와 컴파일 가능한 예제 동기화가 DoD에 포함되어 있다. | plan에 예제 compile/test 또는 source-name grep 검증을 추가한다. |

## 통합 판정

- P0: 0
- P1: 0
- P2: 2 (운영 receipt 경로, 문서 예제 검증; plan 단계에서 구체화)
- P3: 0

P2는 설계의 진행을 막지 않지만, 구현 plan에 정확한 artifact 경로와 executable example 검증을 넣어야 한다. public API·dependency·remote cancellation을 범위에 넣지 않은 결정은 기존 소스와 선행 이슈에 부합한다.

## SPW-01~05 통합 review gate

- **SPW-01 PASS**: 대상 독자, spec 경로, 기준 SHA, source ledger와 inline fallback 상태를 기록했다.
- **SPW-02 PASS**: spec의 문제·대안·경계·실패 모드·수용 기준을 모두 검토했다.
- **SPW-03 PASS**: 한국어 기술 register, API token 보존, `updateCounts`/`acceptedCount` 용어를 확인했다.
- **SPW-04 PASS**: 결과 필드와 executor의 실제 fallback/no-replay 구현을 source와 대조했다.
- **SPW-05 PASS**: Markdown 표·코드 블록·체크리스트를 재독했고 P2 조치를 plan으로 이관했다.

**Step 2-R verdict: PASS (P0=0, P1=0).**
