# 이슈 #31 CockroachDB 명세 검토

날짜: 2026-06-06
범위: `docs/superpowers/specs/2026-06-06-issue-31-cockroachdb-ddl-boundary-design.md`
게이트: Step 2-R 명세 검토

## 검토 입력 자료

- `bluetape4k-full-feature/references/step-2r-spec-review.md`
- 현재 GitHub 이슈 `bluetape4k/bluetape4k-exposed#31`
- 상위 에픽 `#24`와 완료된 모듈 작업 `#30`
- CockroachDB 공식 PostgreSQL 호환성 문서, 현재 안정 버전 v26.2.2
- CockroachDB 공식 SQL 기능 지원 문서, v26.2
- JetBrains Exposed 1.3.0 공식 지원 데이터베이스 문서
- 현재 `exposed-cockroachdb` 구현 및 인접 방언 모듈

## 게이트 판정

- P0=0
- P1=0
- P2=0
- P3=1
- 게이트: 통과

## 관점별 검토

| 관점 | 결과 | 발견 사항 |
|---|---|---|
| 개발자 | 통과 | P0/P1 없음. 테스트와 README 매트릭스로 범위를 구현할 수 있다. 구현 과정에서 필요성이 입증되지 않는 한 범용 공개 매트릭스 API는 피한다. |
| 보안 | 통과 | P0/P1 없음. 로컬 Testcontainers를 사용하며 로컬 CockroachDB 기본값 외의 자격 증명은 사용하지 않는다. |
| 운영/SRE | 통과 | P0/P1 없음. Testcontainers 직렬 실행과 정확한 검증 명령이 명시되어 있다. |
| 사용자/호출자 | 통과 | P0/P1 없음. 지원하지 않는 PostgreSQL 호환 범위가 명확하며 README 매트릭스가 필요하다. |

## 7단계 검토

| 단계 | 결과 | 근거 |
|---|---|---|
| 보안 | 통과 | 명세는 광범위한 PostgreSQL 별칭 사용을 배제하고, 새로운 인증이나 비밀 정보 접점 없이 미지원 경로 분류를 추가한다. |
| 운영/SRE 신뢰성 | 통과 | Testcontainers 직렬 실행, 결정론적 정리, 안정적인 미지원 경로 단언 규칙이 명시되어 있다. |
| 구조적 영향 | 통과 | 방언 추가는 조건부이며, CockroachDB 근거에서 재정의가 필요하다고 확인되지 않는 한 헬퍼 전용 방식을 기본값으로 유지한다. |
| Kotlin/API 품질 | 통과 | 방언 도입 근거가 없는 한 공개 API를 추가하지 않으며, 새로운 KDoc은 모두 영어로 작성해야 한다. |
| 테스트/타입/무증상 실패 | 통과 | 허용되는 DDL 범주에 PK, 고유 제약/인덱스, 생성 ID, `RETURNING`, 메타데이터, 스키마 차이에 대한 실행 가능한 검사가 포함되어 있다. |
| 성능/안정성 | 통과 | 프로덕션 핫 패스 동작이 없으며, 범위를 제한한 Testcontainers 검사만 계획한다. |
| 문서화/릴리스 준비 상태 | 통과 | README 언어별 문서 쌍, CHANGELOG, 이슈 갱신, PR 완료 조건 요구 사항이 명확하다. |

## 통합 발견 사항

| 우선순위 | 영역 | 발견 사항 | 해결 방안 |
|---|---|---|---|
| P3 | API 범위 | `source-visible compatibility matrix`를 공개 API로 과도하게 구현할 수 있다. | 공개 API가 필요해지지 않는 한 내부 또는 테스트에서만 보이는 매트릭스로 계획에 반영한다. |

진행을 막는 발견 사항은 남아 있지 않다.

## Step 2-R 체크리스트 완료 보고

| 항목 | 상태 | 비고 |
|---|---|---|
| 필수 참조 자료 확인 | 완료 | 판정 전에 `step-2r-spec-review.md`를 읽었다. |
| 현재 이슈 및 소스 근거 검토 | 완료 | #31, #24, #30, CockroachDB/Exposed 공식 문서, 현재 모듈 소스를 검토했다. |
| P0/P1 정규화 | 완료 | 최신 종합 판정에 P0/P1 발견 사항이 없다. |
| P0=0/P1=0 종료 조건 | 완료 | `P0=0`, `P1=0`으로 게이트를 종료했다. |
| 다음 단계 차단 해제 | 완료 | Step 3 계획을 시작할 수 있다. |
