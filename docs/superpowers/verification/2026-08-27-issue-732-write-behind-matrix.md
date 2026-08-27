# Issue #732 Write-Behind 검증 매트릭스

## 계약 스키마

이 문서는 write-behind lifecycle 검증 결과를 `schema: 1`로 기록하는 운영 계약이다.

| 필드 | 의미 |
| --- | --- |
| `schema` | 문서 형식 버전. 현재 `1` |
| `version` | 매트릭스 계약 버전. 현재 `1` |
| `status` | `PASS`, `FAIL`, `PENDING`, `N/A`, `SKIPPED` 중 하나 |
| `required` | 해당 환경에서 반드시 실행해야 하는지 |
| `applicable` | 모듈/DB 조합에 적용되는지 |
| `reason` | 판정 사유. 비어 있을 수 없음 |
| `startedAt`, `finishedAt` | UTC ISO-8601 실행 시각 |
| `evidence` | 테스트 task, 실행 DB, 종료 코드와 로그 경로 |

`required=true`이면서 `applicable=true`인 행은 `PASS`만 완료 증거로 인정한다. `applicable=false`는 의도적인 비적용으로 기록하고 `N/A`만 허용한다. `required=false`인 환경의 `SKIPPED`와 `PENDING`은 성공으로 집계하지 않는다. 누락·중복·알 수 없는 행과 잘못된 상태는 parser가 fail-closed로 거부한다.

`build/verification/write-behind-db-matrix.json`은 `scripts/verification/run_write_behind_matrix.py`가 H2 → PostgreSQL → MySQL 8 순서로 생성한다. `build/verification/write-behind-metrics.json`은 기존 adapter 관측성 inventory의 기준 checksum을 보존한다. coordinator는 새 Micrometer meter family를 추가하지 않으며 `queueDepth`는 metric label이 될 수 없다.

## 데이터베이스 매트릭스

| 영역 | H2 | PostgreSQL | MySQL 8 | 비고 |
| --- | --- | --- | --- | --- |
| `exposed/cache` coordinator unit | required/applicable | required/applicable | required/applicable | DB 비의존 상태 기계이며 receipt에서는 `NONE` 한 행으로 실행 |
| JDBC Caffeine | required/applicable | required/applicable | required/applicable | blocking flush와 close |
| suspended JDBC Caffeine | required/applicable | required/applicable | required/applicable | suspend admission/cancellation |
| R2DBC Caffeine | required/applicable | 비적용 (`N/A`) | 비적용 (`N/A`) | 현재 실제 fixture는 H2만 제공 |
| AutoInc write-behind 사례 | applicable=false인 테스트 존재 | applicable=false인 테스트 존재 | applicable=false인 테스트 존재 | 기존 Exposed ID 계약 |

실행 순서는 H2 → PostgreSQL → MySQL 8이며, 한 데이터베이스 실패 뒤 다음 데이터베이스를 성공으로 승격하지 않는다. Testcontainers가 필요한 행은 컨테이너 기동, 실제 task, 테스트 수와 skip 수를 함께 보존한다.

## 공통 conformance 범위

- 정상 close, 중복 close, close 중 admission
- 큐 포화, 취소된 admission, failed flush와 retry
- close timeout/interruption 및 late callback
- queue depth/worker state의 underflow·terminal 전이
- coordinator token의 단일 settle과 close owner identity
- `CacheHealthReport`와 기존 adapter hook/metric의 의미 보존
- `putAll`은 입력 순서대로 항목을 처리하며 중간 실패 시 앞선 DB/cache side effect와 실패 지점의 예외를 보존한다. 자동 rollback이나 partial 결과 숨김은 제공하지 않는다.
- transient flush 실패는 동일 batch를 최대 8회 재시도하고 backoff는 10ms에서 1초로 상한을 둔다. terminal failure는 `FAILED`로 남고 보류 batch는 자동 outbox/dead-letter로 이동하지 않는다.
- accepted queue handoff 뒤 cache publication이 실패하면 해당 key를 invalidate하고 원래 예외를 전달한다. close는 publication drain과 worker drain을 모두 확인한 뒤 invalidate한다.

## 롤아웃 규칙

1. `exposed/cache` unit 및 ABI를 먼저 통과시킨다.
2. JDBC, suspended JDBC, R2DBC adapter를 각각 H2에서 검증한다.
3. 동일 task를 PostgreSQL, MySQL 8 순서로 반복한다.
4. required/applicable 행이 모두 통과한 뒤에만 최종 closeout을 기록한다.

애플리케이션이 durable outbox 또는 dead-letter 복구를 요구하면 저장·재생·중복 제거·알림을 애플리케이션 소유 경계에서 구현해야 한다. coordinator는 메모리 queue의 수명과 상태만 조정하며 durable 복구 저장소를 만들지 않는다. 이 문서는 구현 branch의 검증 스키마와 판정 규칙을 고정하고, 실제 실행 checksum과 CI run은 workflow가 생성한 receipt에 기록한다.
