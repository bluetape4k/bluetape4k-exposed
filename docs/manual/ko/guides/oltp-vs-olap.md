---
title: "OLTP와 OLAP 경로 선택"
locale: "ko"
releaseRef: "1.12.1"
---

# OLTP와 OLAP 경로 선택

OLTP와 OLAP는 제품에 영구적으로 붙는 이름이 아니라 workload의 모양입니다. 요청을 어디서 commit해야 하는지 먼저 정하고, 그 이력을 어디서 훑고 집계할지 선택하세요.

## 작업 성격 비교

| 관점 | 트랜잭션 경로 | 분석 경로 |
| --- | --- | --- |
| 대표 어댑터 | PostgreSQL, MySQL, CockroachDB | DuckDB, ClickHouse, Trino, BigQuery, StarRocks |
| 지연 목표 | 짧은 요청 응답과 lock 시간 | scan·집계 처리량과 queue 시간 |
| 쓰기 모델 | 작은 원자 단위, 제약, rollback 또는 retry | append·batch·job 중심, 원자성은 제품별로 다름 |
| 스키마 | migration으로 관리하는 관계형 계약 | 엔진·connector별 DDL, partition/order 선택 |
| paging | index와 안정적인 keyset/order | 먼저 집계하고 필요한 결과만 stream/page 처리 |
| 로컬 테스트 | PostgreSQL/MySQL/CockroachDB 컨테이너 | 임베디드 DuckDB 또는 엔진·emulator 컨테이너 |

이 표는 어느 한쪽이 언제나 낫다고 말하지 않습니다. PostgreSQL 집계가 가장 단순하고 정확할 수도 있고, 요청 전체를 되돌려야 한다면 ClickHouse 쓰기가 맞지 않을 수도 있습니다.

## commit 소유자를 분명히 하기

트랜잭션 변경은 PostgreSQL·MySQL에서 commit하거나 CockroachDB 작업 전체를 재시도합니다. 분석 저장소에는 commit 뒤 outbox, CDC, 중복 실행에 안전한 job으로 전달하세요. ClickHouse·Trino·StarRocks wrapper는 Exposed 블록을 원자적으로 만들지 않으며 BigQuery 호출도 각각 별도 REST Job입니다.

## 분석 실행 경계 선택

- DuckDB: 서비스 없이 로컬 파일을 분석합니다. 인메모리 상태가 연결마다 다름을 주의하세요.
- ClickHouse: 엔진 DDL과 batch 수집에 맞지만 autocommit과 중복 제거를 설계해야 합니다.
- Trino: 여러 catalog를 함께 조회합니다. connector의 pushdown·DDL·DML을 직접 확인하세요.
- BigQuery: page token, dry run, label, 과금 byte 상한을 갖춘 직접 Query Job 경로입니다.
- StarRocks: 1.11은 좁은 로컬 smoke path만 제공하므로 근거를 더하면서 범위를 넓힙니다.

## 스키마, 테스트, dry run

운영 스키마는 migration이나 엔진 도구로 관리합니다. 트랜잭션 테스트는 실제 DB 컨테이너에서, 분석 어댑터 테스트는 해당 엔진·emulator에서 실행하세요. 작은 운영 유사 smoke test도 남깁니다. BigQuery dry run은 문법·권한·비용을 검증하지만 결과의 정확성까지 보장하지는 않습니다.

## 안전하게 확장하는 순서

1. OLTP 원본에서 요청과 트랜잭션 경계를 검증합니다.
2. 이벤트·내보내기 계약과 idempotency key를 정합니다.
3. 작은 분석 fixture를 적재해 원본 결과와 비교합니다.
4. 지연, scan 양, batch 크기, 재시도를 측정합니다.
5. 어댑터의 실제 동작에 맞춰 paging이나 streaming을 붙입니다. 목록을 먼저 만드는 Flow는 cursor가 아닙니다.

[ClickHouse OLTP/OLAP 예제](../../../../examples/exposed-clickhouse-oltp-olap/README.ko.md)로 분리 구조를 실행하고 [BigQuery dry-run 예제](../../../../examples/exposed-bigquery-dry-run/README.ko.md)로 비용 검증을 익히세요. 모듈별 한계는 [어댑터 표](database-adapter-matrix.md)에서 확인할 수 있습니다.
