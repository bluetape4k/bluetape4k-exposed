# 이슈 #31 CockroachDB 계획 검토

날짜: 2026-06-06
범위: `docs/superpowers/plans/2026-06-06-issue-31-cockroachdb-ddl-boundary-plan.md`
게이트: Step 3-R 계획 검토

## 검토 입력 자료

- `bluetape4k-full-feature/references/step-3r-plan-review-perspectives.md`
- `bluetape4k-full-feature/references/step-3r-plan-review.md`
- 명세: `docs/superpowers/specs/2026-06-06-issue-31-cockroachdb-ddl-boundary-design.md`
- 현재 `exposed-cockroachdb` 모듈
- 기존 Trino, DuckDB, StarRocks, BigQuery 방언 패턴

## 게이트 판정

- P0=0
- P1=0
- P2=0
- P3=1
- 게이트: 통과

## 관점별 검토

| 관점 | 결과 | 발견 사항 |
|---|---|---|
| 구현 담당자 | 통과 | 작업 순서가 올바르다. 매트릭스와 테스트를 먼저 수행하고, 근거가 있을 때만 방언을 도입한다. |
| 테스트 엔지니어 | 통과 | 허용되는 DDL 범주가 명시된 테스트와 대응하며, Testcontainers 검증은 직렬로 대상 범위에 한정해 실행한다. |
| 아키텍트 | 통과 | 모듈 경계를 `exposed-cockroachdb`로 유지하며, 근거가 있을 때만 공개 API를 확장한다. |
| 배포 담당자 | 통과 | README 언어별 문서 쌍, CHANGELOG, 교훈, PR 본문, 리뷰, CI 모니터링이 작업에 배정되어 있다. |

## 7단계 검토

| 단계 | 결과 | 근거 |
|---|---|---|
| 보안 | 통과 | 새로운 비밀 정보나 인증 접점이 없으며, 지원하지 않는 SQL 검사는 깨지기 쉬운 전체 메시지 단언을 피한다. |
| 운영/SRE 신뢰성 | 통과 | 범위를 제한한 Testcontainers 검증, 정리 보호 장치, `--rerun-tasks` 검증이 계획되어 있다. |
| 구조적 영향 | 통과 | #30에서 모듈을 등록했으므로 설정이나 CI 변경은 예상하지 않으며, 방언 도입은 조건부다. |
| Kotlin/API 품질 | 통과 | 공개 API가 필요하다는 근거가 없는 한 매트릭스는 내부 또는 테스트에서만 보이며, 방언을 추가할 때만 KDoc을 작성한다. |
| 테스트/타입/무증상 실패 | 통과 | 테스트에서 성공, 중복 실패, 메타데이터, 원시 `RETURNING`, 스키마 차이가 없는 상태를 다룬다. |
| 성능/안정성 | 통과 | 프로덕션 핫 패스가 없으며, 컨테이너 시작은 계속 싱글턴 기반으로 직렬 수행한다. |
| 문서화/릴리스/근거 | 통과 | README 매트릭스, CHANGELOG, 조사 자료 보존, 리뷰 산출물, 교훈, PR 완료 조건을 다룬다. |

## 통합 발견 사항

| 우선순위 | 영역 | 발견 사항 | 해결 방안 |
|---|---|---|---|
| P3 | Exposed API 변동 | 현재 이 저장소에서 `SchemaUtils.statementsRequiredToActualizeScheme`를 사용하는 곳이 없으므로 정확한 API 형태를 컴파일로 확인해야 한다. | 계획에서 이미 컴파일과 테스트를 요구하며, API가 다를 때 사용할 대안도 기록한다. |

진행을 막는 발견 사항은 남아 있지 않다.

## Step 3-R 체크리스트 완료 보고

| 항목 | 상태 | 비고 |
|---|---|---|
| 필수 참조 자료 확인 | 완료 | 판정 전에 Step 3-R 참조 자료 2개를 모두 읽었다. |
| 명세 요구 사항 대응 | 완료 | 매트릭스, 테스트, 방언 결정, README, CHANGELOG, 검증, 리뷰, PR 작업이 대응되어 있다. |
| 작업 순서 점검 | 완료 | 근거 기반 방언 결정은 헬퍼 전용 테스트 후에 수행한다. |
| P0/P1 정규화 | 완료 | 최신 종합 판정에 P0/P1 발견 사항이 없다. |
| P0=0/P1=0 종료 조건 | 완료 | `P0=0`, `P1=0`으로 게이트를 종료했다. |
| 구현 차단 해제 | 완료 | Step 4를 시작할 수 있다. |
