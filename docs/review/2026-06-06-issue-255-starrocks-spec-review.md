# Issue #255 StarRocks 명세 검토

날짜: 2026-06-06
범위: `docs/superpowers/specs/2026-06-06-issue-255-starrocks-module-design.md`
게이트: 2-R 단계 명세 검토

## 검토 입력 자료

- `bluetape4k-full-feature/references/step-2r-spec-review.md`
- 현재 GitHub 이슈 `bluetape4k/bluetape4k-exposed#255`
- 상위 조사 문서 `docs/superpowers/research/2026-06-06-issue-227-olap-local-testability.md`
- StarRocks JDBC, Docker 빠른 시작, DataGrip, CREATE TABLE 공식 문서
- `com.starrocks:starrocks-connector-j:1.1.1`의 Maven Central 메타데이터
- 기존 `exposed-trino`, `exposed-clickhouse`, `exposed-duckdb` 모듈 패턴

## 게이트 판정

- P0=0
- P1=0
- P2=0
- P3=0
- 게이트: 통과

## 반복 검토 기록

| 반복 | 검토 결과 | 심각도 | 조치 |
|---|---|---:|---|
| 1 | 명세는 공식 근거 없이 `default_catalog.default`를 기본 테스트/사용자 데이터베이스로 사용할 수 있다고 가정했다. | P1 | 수정: `database`를 명시하도록 하고, `default_catalog.<test_database>`에 연결하기 전에 테스트 데이터베이스를 생성해야 하며, 데이터베이스 없는 URL은 드라이버가 허용하는 경우 부트스트랩/준비 상태 확인에만 사용하도록 제한했다. |
| 1 | 명세에 새 공개 JDBC 드라이버의 의존성 라이선스 근거 요구 사항이 없었다. | P1 | 수정: Maven Central POM의 라이선스 근거를 기록했으며, 구현 시 드라이버를 셰이딩하거나 재패키징하지 않고 이 내용을 PR 근거에 포함해야 한다. |

## 7단계 검토

| 단계 | 결과 | 근거 |
|---|---|---|
| 보안 | 통과 | SaaS 자격 증명을 사용하지 않는다. root/비밀번호 없음 설정은 로컬 컨테이너 범위로 한정한다. 비어 있거나 잘못된 연결 입력은 DriverManager 호출 전에 실패해야 한다. 의존성 라이선스 근거가 필요하다. |
| 운영/SRE 신뢰성 | 통과 | Docker 메모리/디스크/포트 요구 사항을 문서화했다. Testcontainers는 직렬로 실행한다. 대용량 이미지로 인한 CI 위험에는 Nightly 대체 경로가 있다. |
| 구조적 영향 | 통과 | 모듈 이름은 설정 자동 탐색 규칙을 따른다. PR 전에 AGENTS, README, 워크플로, 카탈로그, BOM/검사 스크립트 검증이 필요하다. |
| Kotlin/API 품질 | 통과 | 공개 타입은 기존 OLAP 모듈 스타일을 따른다. API KDoc과 검증 요구 사항이 명확하다. 광범위한 추상화를 도입하지 않는다. |
| 테스트/타입/무응답 실패 | 통과 | 스모크 테스트에서 연결, 명시적 DB 부트스트랩, 픽스처 설정, SELECT, DatabaseMetaData를 통한 카탈로그/스키마/테이블/열 탐색을 입증해야 한다. |
| 성능/안정성 | 통과 | 성능에 대한 주장은 없다. 무거운 컨테이너는 알려진 위험이며 직렬 실행 및 문서화 요구 사항으로 관리한다. |
| 문서화/릴리스 준비 상태 | 통과 | 루트 및 모듈 README 로케일 세트, 공개 비목표, 의존성 코드 조각, 로컬 실행 요구 사항, 워크플로 배치, PR 근거가 필요하다. |

## 종합 검토 결과

1차 반복 검토의 수정 이후 차단 및 비차단 검토 결과가 남아 있지 않다.

## 2-R 단계 체크리스트 완료 보고

| 항목 | 상태 | 비고 |
|---|---|---|
| 필수 참고 자료 로드 | 완료 | 판정 전에 `step-2r-spec-review.md`를 읽었다. |
| 검토 범위 기록 | 완료 | 명세 경로와 근거 입력 자료를 나열했다. |
| P0/P1 정규화 | 완료 | 1차 반복 검토의 P1 결과를 수정하고 재검토했다. |
| P0=0/P1=0 종료 조건 | 완료 | 최신 통합 판정: `P0=0`, `P1=0`. |
| 다음 단계 차단 해제 | 완료 | 3단계 계획을 시작할 수 있다. |
