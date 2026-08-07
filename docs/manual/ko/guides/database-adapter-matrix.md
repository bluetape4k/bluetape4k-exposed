---
title: "데이터베이스 어댑터 표"
locale: "ko"
releaseRef: "1.12.1"
---

# 데이터베이스 어댑터 표

SQL 문법이 비슷하다는 이유로 어댑터를 고르지 말고, 실행 위치와 상태 소유권부터 정하세요. 1.11에는 아래 여덟 어댑터가 있습니다. Druid는 이 버전에 포함되지 않습니다.

![1.11 데이터베이스 어댑터 지도](../../assets/database/adapter-map.png)

## 1.11에서 확인한 범위

| 어댑터 | 실행 경계 | 쿼리·DDL 근거 | 트랜잭션 경계 | 테스트 경로 | 가장 큰 제약 |
| --- | --- | --- | --- | --- | --- |
| PostgreSQL | JDBC 확장 타입 | pgvector, PostGIS, `tstzrange` | 일반 JDBC commit/rollback | PostgreSQL Testcontainers | optional driver와 extension은 호출자 소유 |
| MySQL 8 | JDBC GIS 확장 | JTS geometry와 공간 함수 | 일반 JDBC commit/rollback | MySQL Testcontainers | 연결 모듈이 아니라 GIS 계층 |
| CockroachDB | PostgreSQL JDBC 어댑터 | 기본 스키마와 compatibility ledger | `40001` 때 serializable 블록 전체 재시도 | CockroachDB Testcontainers | PostgreSQL과 부분 호환 |
| DuckDB | 임베디드 JDBC | 로컬 create/insert/select | 연결 범위 DuckDB 트랜잭션 | 컨테이너 없이 임베디드 | 인메모리는 연결별 DB, Flow는 전체 적재 |
| ClickHouse | JDBC OLAP 어댑터 | 엔진 DDL, 사용자 타입·함수, batch insert | autocommit, rollback은 no-op | ClickHouse Testcontainers와 예제 | 관계형 제약·upsert·returning 미지원 |
| Trino | coordinator JDBC 어댑터 | SELECT 중심, DDL/DML은 connector에 따름 | autocommit, 여러 문장은 비원자적 | Trino memory connector | 운영 connector를 다시 검증해야 함 |
| BigQuery | SQL 생성 + REST Query Job | SELECT, 기본 DML/DDL, dry run, page token | REST Job마다 독립 | emulator/Testcontainers와 dry-run 예제 | JDBC 트랜잭션과 Job 사이 rollback 없음 |
| StarRocks | 좁은 Connector/J smoke 어댑터 | 로컬 AIO DDL/insert/select | autocommit-only wrapper | StarRocks All-in-One | MySQL·운영 환경과 완전 동등하지 않음 |

## 드라이버와 서비스 소유권

PostgreSQL·MySQL 확장 모듈의 드라이버는 compile-only라 애플리케이션이 필요한 runtime을 넣습니다. 나머지 모듈은 실행 client나 driver를 노출합니다. 컨테이너 테스트는 1.11 경로의 근거일 뿐 운영 provisioning, credential, TLS, migration, topology까지 라이브러리가 맡는다는 뜻은 아닙니다.

## 조회, paging, batch

트랜잭션 JDBC 계열은 JDBC 모듈의 batch와 paging 패턴을 사용합니다. DuckDB·ClickHouse·Trino의 `queryFlow`는 트랜잭션 안에서 전체 `Iterable`을 먼저 목록으로 만드므로 server cursor가 아닙니다. BigQuery `toFlow`는 REST page token을 따라 페이지별로 emit합니다. StarRocks 1.11은 단건 smoke path만 확인했으므로 batch와 paging에는 추가 근거가 필요합니다.

## 스키마와 미지원 기능

운영 스키마 변경은 migration으로 관리하세요. ClickHouse·Trino·BigQuery·StarRocks의 DDL 어댑터는 일부 경로만 지원하며 관계형 기능 전체를 보장하지 않습니다. CockroachDB는 compatibility ledger를 제공합니다. PostgreSQL·MySQL 확장은 타입과 식을 더할 뿐 서버 extension이나 index를 설치하지 않습니다.

## 선택 순서

1. 트랜잭션 상태를 바꾸는 요청이면 PostgreSQL, MySQL, CockroachDB부터 비교합니다.
2. 로컬 분석 SQL이면 DuckDB부터 봅니다.
3. OLAP 엔진이 정해져 있다면 해당 어댑터의 쓰기·DDL 경계를 받아들일 수 있는지 확인합니다.
4. 여러 catalog를 함께 조회하면 Trino를 검토하고 운영 connector를 직접 검증합니다.
5. BigQuery 비용 검증이 중요하면 REST 어댑터와 dry run을 사용합니다.

트랜잭션 원본과 분석 sink를 함께 쓰기 전에 [OLTP와 OLAP](oltp-vs-olap.md)을 읽으세요.

## 소스

- [1.11 manifest](../../manifest.yaml)
- [1.11 프로젝트 등록](../../../../settings.gradle.kts)
- [ClickHouse 예제](../../../../examples/exposed-clickhouse-oltp-olap/README.ko.md)
- [BigQuery dry-run 예제](../../../../examples/exposed-bigquery-dry-run/README.ko.md)
