# Issue #256 Druid JDBC 명세 검토

날짜: 2026-07-05
범위: `docs/superpowers/specs/2026-07-05-issue-256-druid-jdbc-design.md`
게이트: Step 2-R 명세 검토

## 검토 입력

- 현재 GitHub 이슈 `bluetape4k/bluetape4k-exposed#256`
- 상위 조사 문서 `docs/superpowers/research/2026-06-06-issue-227-olap-local-testability.md`
- 보존된 위키 문서 `bluetape4k-wiki/research/2026-07-05-apache-druid-jdbc-query-only.md`
- Apache Druid 공식 JDBC, 메타데이터, Docker 및 로컬 빠른 시작 문서
- 기존 `exposed-trino`, `exposed-duckdb`, `exposed-clickhouse` 모듈 패턴

## 게이트 판정

- P0=0
- P1=0
- P2=1
- P3=0
- 게이트: PASS

## 발견 사항

| 발견 사항 | 심각도 | 조치 |
|---|---:|---|
| Druid 공식 빠른 시작은 메모리 사용량이 많고 현재 `localhost:8888`에서 접근할 수 있는 로컬 Druid 서비스가 없다. 전용 픽스처 구성이 없으면 CI에서 컨테이너를 자동으로 시작하는 방식은 신뢰하기 어렵다. | P2 | 명세에서 모듈을 쿼리/메타데이터 API로 제한하고, 환경 변수로 제어하는 스모크 테스트와 수동/로컬 컨테이너 명령을 문서화하며, 광범위한 DDL/DML/리포지토리 API는 범위에서 제외한다. |

## 7단계 검토

| 단계 | 결과 | 근거 |
|---|---|---|
| 보안 | PASS | 기본적으로 자격 증명이 필요하지 않고, 인증 정보는 `Properties`로 전달하며, 비밀 정보를 커밋하지 않는다. |
| 운영/SRE 신뢰성 | PASS | Router/Broker 고정성과 `transparent_reconnection`을 명시하며, 고비용 컨테이너 시작을 기본 CI에 숨겨 넣지 않는다. |
| 구조적 영향 | PASS | 새 모듈은 `exposed/*` 자동 탐색 방식을 따르며 README/AGENTS/CI/Nightly 등록이 필요하다. |
| Kotlin/API 품질 | PASS | 명세는 조회 전용 도우미를 요구하고 광범위한 Exposed 방언 호환성은 배제한다. |
| 테스트/타입/조용한 실패 | PASS | 단위 테스트와 환경 변수로 제어하는 픽스처 스모크 테스트가 필요하다. 로컬 Druid가 없는 상태는 무시하지 않고 P2 증거 공백으로 기록한다. |
| 성능/안정성 | PASS | 성능에 관한 주장은 없으며, 일시 중단 API에서 블로킹 JDBC 작업은 `Dispatchers.IO`를 사용해야 한다. |
| 문서화/릴리스 준비 | PASS | README 로케일 세트와 공개 범위 제외 섹션이 필요하다. |

## Step 2-R 체크리스트 완료 보고

| 항목 | 상태 | 비고 |
|---|---|---|
| 검토 범위 기록 | 완료 | 명세 경로와 증거 입력을 나열했다. |
| P0/P1 정리 | 완료 | P0/P1 발견 사항이 없다. |
| 비차단 발견 사항 기록 | 완료 | P2 스모크 환경 공백과 완화 방안을 기록했다. |
| 다음 단계 차단 해제 | 완료 | 조회 전용 구현 계획을 진행할 수 있다. |
