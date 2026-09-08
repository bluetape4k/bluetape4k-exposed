# #857 구현 검증 기록

## 현재 판정

PENDING — 로컬 API 구현, ABI, 양쪽 README, 전체 모듈 테스트·정적 분석·benchmark 컴파일, JMH와 12개 메모리/JFR 프로파일을 검증했다. 독립 리뷰 3건을 수집했고 query 취소·rendezvous send 대기 경계를 보강했다. 다만 실제 드라이버 응답 timeout/행 한도와 승인 명세의 모든 조합은 실행하지 않았고, 중앙 문서/wiki와 PR 생성·push·머지는 별도 범위다.

## 실행 근거

2026-09-09 KST, 승인된 `feat/issue-857-clickhouse-streaming` worktree에서 실행했다. 테스트 프로세스에만 정상 Colima socket 설정을 전달했다. 아래 Gradle 명령에는 모두 `--no-build-cache --console=plain`을 사용했다.

| 검증 | 결과 |
|---|---|
| queryList 신규 테스트, 구현 전 | compileTestKotlin 실패: queryList 미정의, RED |
| ClickHouseExtensionsTest | exit 0, 10 tests, 전체 수집·기존 Flow 호환·예외·SQL 시도 1/2·실제 취소 |
| 신규 Query Flow 테스트, 구현 전 | compileTestKotlin 실패: query/mapper 인자 없음, RED |
| `test --tests '*ClickHouse*Test'` | exit 0, ClickHouse 모듈 전체 필터 실행; 별도 필터 수를 전체 합계로 합산하지 않음 |
| `test --tests '*ClickHouseQueryLifecycleTest'` | exit 0, 21 cases; 실제 ClickHouse와 Hikari pool 2, query/next/mapper/send 취소 경계 포함 |
| `test --tests '*ClickHouseQueryFlowTest'` | exit 0, 7 tests; duplicate/alias/decimal/string 및 실제 컬럼 bound predicate 포함 |
| `checkKotlinAbi detekt test` 최종 실행 | exit 0, 실제 전체 195 tests; nullable/date/aggregate, fixture close 위임, logger 비노출, 사전 취소 포함 |
| `:benchmark-exposed-benchmark:compileBenchmarkKotlin` | exit 1; `Cannot access 'org.testcontainers.clickhouse.ClickHouseContainer' which is a supertype of 'ClickHouseServer'` |
| 승인 후 `:benchmark-exposed-benchmark:compileBenchmarkKotlin :benchmark-exposed-benchmark:benchmarkBenchmarkJar` | exit 0; `libs.testcontainers.clickhouse`를 benchmarkImplementation에 추가한 뒤 benchmark 소스와 JAR 생성 |
| `:benchmark-exposed-benchmark:detekt` | exit 0 |
| `:benchmark-exposed-benchmark:tasks --all` | exit 0; `benchmarkBenchmarkJar`, `profileClickHouseStreaming` 이름 확인 |

ClickHouse 테스트 명령의 task prefix는 `:bluetape4k-exposed-clickhouse:`이며 XML은 `exposed/clickhouse/build/test-results/test/`에 있다. benchmark 명령은 `:benchmark-exposed-benchmark:`를 사용한다. 서로 다른 필터 실행의 테스트 수를 전체 모듈 성공 수로 합산하지 않는다.

## 조사한 실패와 수정

- 일반 IllegalStateException identity 비교는 coroutine stacktrace recovery의 예외 복사로 실패했다. pinned coroutines 소스와 기존 JDBC 테스트를 확인하고 추가 상태를 가진 테스트 예외로 원인 동일성을 관측했다. production 예외 처리는 이 문제 때문에 변경하지 않았다.
- 신규 구현의 `select` 확장 import 누락을 컴파일로 발견하고 수정했다.
- dispatcher 테스트의 정확한 스레드명 비교는 coroutine debug 접미사로 실패했다. 지정한 executor 스레드명 접두사를 검사하며 query와 mapper 양쪽을 검증한다.
- T1의 계획상 개별 커밋을 T2 전에 하지 못했다. 이미 통과한 T1 테스트를 전체 검증에서 다시 실행하고 T1~T4 구현을 하나의 로컬 체크포인트로 기록한다. 미실행 커밋을 완료로 표시하거나 계획의 과거 기록을 바꾸지 않는다.
- detekt 10건을 발견했다. 직접 커서 처리와 채널 수명 처리를 분리하고 finally의 새 예외 throw를 없앴다. 사용자 Throwable 원형 보존과 취소 우선 재전파에 필요한 generic catch/3개 throw만 이유를 명시하여 제한적으로 suppress했다. 최종 detekt는 exit 0이다.
- 초기 Database 메타데이터 연결의 close 실패는 트랜잭션 close 실패와 정책이 다르다. pinned Database.kt의 metadata finally를 확인하고, 트랜잭션 종료 주입 테스트는 초기화를 마친 후 수행했다. Statement/트랜잭션 close 오류 로그와 풀 재사용은 통과했다.
- 독립 API/benchmark 리뷰가 지적한 프로파일 기준선 오염은 List 참조 해제, 1,000행 bounded warmup, 5회 GC settle, 교대 실행으로 보정했다. 보정 후 기준선은 100k/1m에서 약 22.7~22.9MB로 안정됐고, 이전 수치는 최종 표에서 제거했다.
- 독립 lifecycle 리뷰가 지적한 query 진입과 rendezvous `send` 대기 증거를 테스트에 추가했다. 실제 소켓 응답 timeout·driver row limit과 모든 close-failure 조합은 테스트 컨테이너·driver 계약에 종속되어 별도 후속 검증으로 남겼다.

## 벤치마크 승인 경계

기존 benchmark source set에 JMH 비교와 fresh JVM 프로파일 진입점을 작성했다. ClickHouse 모듈의 `testImplementation(libs.testcontainers.clickhouse)`는 benchmark로 전이되지 않으며, 기존 benchmark의 Testcontainers 의존성은 MySQL/PostgreSQL뿐이다. 따라서 재사용하기로 한 `ClickHouseServer.Launcher`의 상위 클래스가 컴파일 classpath에 없다.

필요한 최소 추가 범위는 `benchmark/exposed-benchmark/build.gradle.kts`의 `add("benchmarkImplementation", libs.testcontainers.clickhouse)` 한 줄이다. 기존 catalog 버전을 그대로 사용하고 공개 라이브러리 runtime은 변경하지 않는다. 이 줄을 추가한 뒤 benchmark 소스와 JAR 컴파일이 exit 0으로 통과했다. 별도 Docker 실행기나 환경 의존 endpoint로 측정 fixture를 조용히 교체하지 않는다.

## 메모리 프로파일 결과

`benchmark/exposed-benchmark/build/reports/clickhouse-profile/` 아래에 12개 조합의 JSON·raw CSV·live CSV·JFR을 생성했다. `flow/1,000,000/run=2`는 Gradle configuration-cache 직렬화 오류로 한 번 실패했지만 동일 조합을 `--no-configuration-cache`로 재실행해 exit 0을 확인했다. 최종 실행은 `run=1/3: list→flow`, `run=2: flow→list` 순서로 고정했으며, 각 JSON의 `count`와 `checksum`은 모든 조합에서 각각 행 수와 기대 합계와 일치했다.

| API | rows | delta live heap 중앙값 | raw heap peak 중앙값 | RSS peak 중앙값 | 1m/100k live 비율 |
|---|---:|---:|---:|---:|---:|
| flow | 100,000 | 2,660,696 B | 44,927,912 B | 325,856 KiB | — |
| flow | 1,000,000 | 2,705,872 B | 159,776,408 B | 379,472 KiB | **1.02x** |
| list | 100,000 | 3,944,736 B | 82,472,408 B | 326,896 KiB | — |
| list | 1,000,000 | 29,148,136 B | 261,918,432 B | 511,312 KiB | **7.39x** |

Flow의 forced-GC 후 live heap 증가는 10배 행 수에서 1.02배로 계획의 3배 상한을 충족한다. List는 같은 조건에서 7.39배 증가했다. raw heap peak은 누적 allocation watermark이므로 retained heap 판정으로 사용하지 않는다. JFR `jdk.OldObjectSample`에서 Flow 1m에는 `Object[1000000]`/`ArrayList` 결과 보관이 없고 확인된 최대 객체는 약 293 KiB HTTP buffer였다. List 1m에는 `java.lang.Object[1000000]`(약 3.8 MB, `ArrayList.toArray`)가 retained sample로 남아 있었다. Flow allocation sample의 큰 비중은 coroutine/ThreadLocal 및 ClickHouse LZ4 read 경로의 누적 할당이며 retained result array가 아니다.

따라서 AC-08은 **live-retention 관점 PASS**로 기록한다. JFR sample의 부재만으로 driver 내부 버퍼가 없다고 단정하지 않으며, raw heap/RSS/direct buffer는 live heap과 함께 해석한다. 원시 산출물은 현재 worktree의 build 디렉터리에 있으며 Git 추적 대상이 아니다.

## JMH 처리량

`:benchmark-exposed-benchmark:clickHouseBenchmark`를 2026-09-09 KST에 실행했다. `benchmark/exposed-benchmark/build/reports/benchmarks/clickHouse/<timestamp>/benchmark.json`에 원본을 보관했으며, warmup 1회·1초 iteration 3회를 사용했다.

| API | rows | score (ops/s) |
|---|---:|---:|
| `queryList` (`collected`) | 100,000 | 76.556 ± 42.276 |
| `queryList` (`collected`) | 1,000,000 | 5.582 ± 8.882 |
| `queryFlow` (`streamed`) | 100,000 | 2.072 ± 3.211 |
| `queryFlow` (`streamed`) | 1,000,000 | 0.147 ± 1.118 |

`queryFlow`는 rendezvous backpressure와 행 단위 매핑을 사용하므로 이 수치는
처리량·메모리 trade-off를 나타낼 뿐 streaming이 더 빠르다는 보장이 아니다.
JMH 결과와 forced-GC profile을 latency SLO로 결합하지 않는다.

## 차트와 분석 산출물

수치의 단위를 섞지 않도록 live heap과 첫 detached 항목 지연을 두 panel로 분리한
EN/KO chart를 추가했다. source summary와 재현 가능한 renderer는
`docs/benchmarks/exposed-benchmark-2026-09-09-issue-857/`에 두었고, README에는
PNG만 노출한다.

| 자산 | 근거 |
|---|---|
| `docs/images/readme-charts/exposed-clickhouse-query-streaming-issue-857.svg/.png` | EN SVG/XML·CairoSVG scale 2·visual audit PASS; 3200×2360, opaque, bbox occupancy 0.851 |
| `docs/images/readme-charts/exposed-clickhouse-query-streaming-issue-857.ko.svg/.png` | KO SVG/XML·CairoSVG scale 2·visual audit PASS; 3200×2360, opaque, bbox occupancy 0.846 |
| `exposed-clickhouse-query-streaming-issue-857.semantic.json` | semantic audit PASS: nodes=8, edges=0, chart budget 내 |
| SVG text/asset pair | `text_hazards=0`, `code_without_highlight=0`, 17 SVG/PNG pairs, missing pair=0; EN/KO README chart ref 각 1개 |

차트는 10배 행 수에서 `queryFlow` live heap `1.02x` 대 `queryList` `7.39x`,
첫 항목 약 `38–39 ms` 대 `80.5/457.8 ms`라는 선택 정보를 빠르게 보여준다.
JMH 처리량은 오차가 큰 별도 표로 유지하고 chart 축에 억지로 합치지 않았다.

## 확인한 수명 계약

실제 Connection/PreparedStatement/ResultSet에 위임하는 테스트 전용 decorator를 사용했다. 정상/take/mapper/collector/query/next/send/cancel/SQL 실패 후 활성 자원과 Hikari active connection이 0이며 호출자 pool은 열린 상태다. 직접 ResultSet close 실패는 기존 예외에 suppressed로 남는다. 느린 소비자에서 두 번째 행만 매핑한 뒤 rendezvous `send`가 대기하고, 취소 후 생산자 join이 끝나야 호출이 반환한다. 외부 트랜잭션은 별도 연결과 연결 지역 marker를 유지한다. 이는 서버 인증·tenant 보안 전체와 실제 driver read timeout을 입증하는 테스트가 아니다.

## 남은 항목

- [x] fixture 자체 위임, Statement/connection 정리 실패와 로그 정책의 대표 경로
- [x] nullable/date/aggregate 매핑, lifecycle-only logger의 mapper 예외 비노출
- [x] ABI와 bilingual README, 전체 모듈 테스트·detekt
- [ ] 승인 명세의 모든 정리 실패 조합, 실제 driver 응답 timeout/row limit, 전체 매트릭스 최종 대조
- [x] benchmark 전용 ClickHouse Testcontainers 의존성 승인과 컴파일
- [x] 100k/1m 실제 메모리 및 JFR 반복 실측(12개 조합; configuration-cache 재실행 포함)
- [x] 최종 독립 API/benchmark/code/lifecycle 리뷰 수집, query/send 경계 보강
- [x] 교훈 기록 완료; 최종 통합 리뷰는 남은 미입증 범위를 PENDING으로 유지
- [ ] 중앙 매뉴얼과 wiki 게시의 별도 범위 확인

작성 검토: SPW-01~05 적용. 주장과 명령 결과를 구분하고, 부분 검증·환경 경고·남은 범위를 명시했다. JDK 25 native access/Unsafe 경고는 기존 의존성에서 발생했으며 새로운 성공 증거로 간주하지 않는다.
