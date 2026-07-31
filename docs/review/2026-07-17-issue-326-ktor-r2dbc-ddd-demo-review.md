# 이슈 326 Ktor R2DBC 캐시 및 DDD 데모 리뷰

## 범위

- `examples/ktor-exposed-demo` 주문 확인 구현 및 테스트
- PostgreSQL Compose 및 직렬화된 Testcontainers 검증
- 영어/한국어 README 쌍
- 아키텍처 및 시퀀스 SVG/PNG 쌍
- 승인된 설계, 계획, 전달 체크리스트 및 영구 교훈

이 리뷰에서는 프로덕션 라이브러리 API, 게시 집계, 버전 카탈로그, CI 워크플로
변경, Spring, Spring Modulith, JaVers 및 이슈 #322를 제외한다.

## 계약 확정

아키텍처 B는 Ktor 라우트 -> `OrderCommandService` ->
`OrderR2dbcCaffeineRepository` -> PostgreSQL 순서로 구현하며, 이후
애플리케이션 소유 게시자가 처리한다. `WRITE_THROUGH`는 PostgreSQL보다 먼저
Caffeine을 변경하며 명시적으로 비원자적이다. 이벤트 인계는 영속화가 반환된
후에만 발생하고 요청 로컬이므로 영구적이지 않다. 명령 반복은 순차 호출에
한해서만 멱등성을 보장하는 것으로 특성화한다.

H2는 기존 JDBC 트랜잭션 개수 라우트에만 남는다. R2DBC 경로, 준비 상태 프로브,
스키마, 저장소 및 주문 개수 라우트는 PostgreSQL을 사용한다.

## 리뷰 영역

| 영역 | 최종 결과 | 근거 |
|---|---:|---|
| 성능 | P0/P1 = 0 | 커넥션 두 개인 데모 풀, 제한된 획득/폐기 대기, O(1) 캐시 준비 상태, 폴링 또는 재시도 루프 없음, 명시적인 크기 조정 주의 사항. |
| 안정성/동시성 | P0/P1 = 0 | 실패 원자적 획득, 역순 정리, 단일 수명주기 리스, 동시 멱등 종료, 기본 데이터베이스 복원, 취소 보상, 순차 멱등성 경계 테스트. |
| 보안/개인정보 보호 | P0/P1 = 0 | 루프백 바인딩, 허용 범위가 넓은 CORS 없음, 본문 없는 교육용 헤더, 정규 UUID 검증, 안정적인 오류 본문, 허용 목록 기반 진단, 응답에 예외 텍스트나 자격 증명 없음. |
| 운영 | P0/P1 = 0 | 프로브 없는 활성 상태, JDBC/R2DBC/캐시 준비 상태, 제한된 풀 대기, 명시적인 실행기 상태, 종료 순서, Compose 상태 검사, 볼륨 보존 및 파괴적 명령, 프로덕션 주의 사항. |
| 개발자/API | P0/P1 = 0 | 예제 로컬 타입만 사용, 기존 저장소/캐시/DDD 계약 재사용, 공개 라이브러리 API 또는 새 모듈 없음, Spring/Modulith/JaVers 의존성 없음. |
| 사용자/문서/다이어그램 | P0/P1 = 0 | 시나리오 우선 이중 언어 가이드, 바이트 단위로 동일한 Bash 블록, 정확한 라우트/오류 표, 비원자적 및 비영구적 한계, 읽기 쉬운 아키텍처 및 시퀀스 자산, 모든 다이어그램 감사. |
| 빌드/테스트 | P0/P1 = 0 | Docker 없는 테스트 32개, PostgreSQL 테스트 4개, 실제 curl 절차, Compose 스모크/초기화 근거, 렌더링 동등성, 링크 검사, 차이 검사. |

최종 수렴 결과는 승인된 설계를 기준으로 **P0 = 0, P1 = 0**이다.

## 발견 사항 및 처리

| 심각도 | 발견 사항 | 처리 |
|---|---|---|
| P1 | 리뷰한 구현 head `9ea8e575`에는 아직 최종 리뷰, 교훈 또는 완료된 체크리스트가 없었으므로 자체 계획에 따른 PR head로 사용할 수 없었음 | 이 리뷰, 교훈, 16/19 체크리스트 상태를 포함한 근거 커밋으로 해결했다. 결과로 나온 정확한 head는 푸시 전에 다시 검증한다. |
| P2 | 일반적인 로컬 Testcontainers 호출에서는 Ryuk가 Colima Docker 소켓 마운트를 시도하며 PostgreSQL 테스트 시작 전에 실패함 | 환경별 문제이며 제품 실패가 아니다. 저장소에 문서화된 `TESTCONTAINERS_RYUK_DISABLED=true`로 다시 실행하면 PostgreSQL 테스트 네 개가 모두 성공한다. |
| P2 | 모듈 로컬 `detekt` 작업이 없고 루트 `detekt`는 `NO-SOURCE`임 | 정적 분석 공백으로 기록했다. 최신 Kotlin 컴파일과 동작/통합 테스트 36개가 실행 가능한 근거다. 모듈 전용 정적 분석 규칙 추가는 이슈 #326 범위 밖이다. |
| P3 | 범용 다이어그램 감사는 이 사용자 정의 SVG의 텍스트 레이블을 분류하지 못하고 `labels=0`으로 보고함 | 대상 불변 조건 검사로 아키텍처 레이블 44개, 시퀀스 레이블 57개, 하나 이상인 카드/경로 수, 번호 배지 14개, alt 프레임 3개, 자산별 `userSpaceOnUse` 마커 두 개를 입증했다. |
| P3 | 실제 PostgreSQL 수명주기 테스트 하나가 가상 시간 `runTest`를 사용했음 | 실제 R2DBC I/O에 저장소 표준 `runSuspendIO(timeout = 30.seconds)`를 사용하도록 수정했다. |
| P3 | Docker 없는 테스트 두 개가 저장소 단언 헬퍼 대신 `kotlin.test.assertFailsWith`를 임포트했음 | `io.bluetape4k.assertions.assertFailsWith`를 사용하도록 수정했으며, 동작은 변경되지 않았다. |

## 인수 조건 매핑

| 기준 | 구현 | 근거 |
|---|---|---|
| JDBC 및 R2DBC 요청 경로 | H2 JDBC 개수와 PostgreSQL 주문 개수/명령 | Docker 없는 라우트 테스트, PostgreSQL 스위트, 실제 실행 절차 |
| 캐시 지원 저장소 시나리오 | `WRITE_THROUGH` 모드의 `OrderR2dbcCaffeineRepository` | 영속화, 캐시 무효화, 캐시 적중, 개수 단언 |
| Spring 중립적인 애그리게이트/이벤트 | `DemoOrder`, `OrderConfirmed`, `OrderCommandService`, `OrderEventPublisher` | 도메인 및 명령 서비스 테스트, 금지된 프레임워크 참조 없음 |
| 영속화 후에만 게시 | 서비스가 스냅숏 생성, 저장, 게시 후 정리 | 순서, 영속화 실패, 게시자 실패, 취소 테스트 |
| 비 Spring 경계 문서화 | 애플리케이션 소유 비영구 게시자 | README 언어 쌍, 아키텍처, 시퀀스, 제한 사항 |
| PostgreSQL이 H2 R2DBC를 대체 | 애플리케이션 소유 풀/데이터베이스 및 스키마 | 의존성 검사, Testcontainers 스위트, Compose 실행 절차 |
| 명시적인 수명주기 소유권 | `KtorExposedDemoResources` 및 종료 보고서 | 획득 실패, 외부 기본값, 동시 종료, 두 번째 수명주기 테스트 |
| 따라 하기 쉬운 예제 | 시나리오, 아키텍처, 시퀀스, 명령, 오류, 제한 사항 | 이중 언어 동등성 매트릭스 및 전체 크기 다이어그램 검사 |

## 검증 근거

- `:examples-ktor-exposed-demo:test --rerun-tasks`: 테스트 32개 통과,
  `testcontainers-lines=0`; `BUILD SUCCESSFUL`.
- `TESTCONTAINERS_RYUK_DISABLED=true
  :examples-ktor-exposed-demo:postgresIntegrationTest --no-parallel
  --rerun-tasks`: 테스트 4개 통과, `BUILD SUCCESSFUL`.
- 실제 서버 실행 절차: 준비 상태에 `jdbc`, `r2dbc`, `cache.orders`가 포함되었다.
  JDBC 개수는 `2`, R2DBC 개수는 `1 -> 2`였고, 최초/GET/반복 상태가 일치했으며
  반복 요청은 게시하지 않았다.
- Compose 설정 및 상태 검사는 `127.0.0.1:5432`에서 통과했다. 일회용 볼륨
  초기화로 `bt4k-issue-326-reset_ktor-exposed-demo-postgres`를 제거했다.
- 두 PNG 모두 새 CairoSVG scale-2 렌더링과 바이트 단위로 일치한다. 모든
  다이어그램 감사에서 도형/끝점/혼합 모서리/스타일 실패가 없었다.
- 대상 다이어그램 개수: 아키텍처 `rects=16 paths=11 labels=44`, 시퀀스
  `rects=36 paths=21 labels=57 badges=14 alt=3`, 각각 고정 단위 마커 두 개.
- 영어/한국어 명령 블록, 상호 링크, 라우트/오류 식별자, 로컬 경로,
  `git diff --check`가 통과했다.
- `.github`, `gradle/libs.versions.toml`, `gradle.properties`,
  `settings.gradle.kts` 아래에는 차이가 없다. 예제 구현에는
  Spring/Modulith/JaVers/H2-R2DBC 참조가 없다.

코드 리뷰 그래프는 인덱싱된 노드를 반환하지 않았다. 대체 수단으로 직접 소스 검사,
정확한 차이 검사, 컴파일된 테스트, 실제 PostgreSQL, 루프백 실행 절차를 사용했다.

## 잔여 위험

- 캐시와 PostgreSQL은 의도적으로 원자적이지 않다.
- 동시 확인은 exactly-once 동작을 보장하지 않는다.
- PostgreSQL 취소 시 커밋 결과가 불명확할 수 있다.
- 이벤트 전달은 영구적이지 않으며 POST 반복으로 복구할 수 없다.
- 시작 DDL, 데모 풀 크기, 동기식 stderr, 준비 상태 드레인 부재는 문서화된
  예제 제약이며 프로덕션 기본값이 아니다.

## 게이트

최종 PR 전 리뷰 게이트: **PASS**. 범위 내 구현과 로컬 근거는 정확한 head 검증
및 PR 생성 준비를 마쳤다. 실제 CI, 리뷰, 스레드가 수렴한 뒤 병합에는 별도의
새 승인이 필요하다.
