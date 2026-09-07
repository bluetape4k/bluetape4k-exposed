# #816 tenant JDBC registry 구현 리뷰

## 범위와 판정

- 기준 SHA: `d98954c1c0e6e8da7132d1c73c0f7e7f96f6da6a` (`origin/develop`).
- 검토한 구현 SHA: `dafe7ddb4dd2161f5e358b21c7bf98270bd2ae12`.
- 대상: 신규 `bluetape4k-exposed-tenant-jdbc` API·구현·테스트, ABI,
  publication/BOM, CI/Nightly 연결, 영어·한국어 문서.
- 제외: PR exact-head CI, Full Nightly, Maven Central 배포, downstream
  `exposed-workshop#269` 이전, Spring/Ktor adapter 추가.
- 최종 심각도: **P0=0, P1=0, P2=0, P3=0**.
- 판정: **로컬 구현 리뷰 PASS**. 세 delivery 검증은 아직 실행하지 않았으므로
  PR 또는 머지 준비 완료로 확대 해석하지 않는다.

## 관점별 리뷰

각 reviewer는 `origin/develop...dafe7ddb` exact diff를 읽었다. 성능·안정성·보안
lane은 발견 사항을 수정한 뒤 같은 구현 SHA를 다시 검토했다. 운영·개발/API·
사용자/호출자 lane은 수정 요청 없이 `COMMENT`를 반환했다. `COMMENT`는 도구와
hosted 검증 공백을 정직하게 남긴 판정이며 P0–P3 발견 사항은 없다.

| 관점 | 소스·검증 근거 | 결과 |
|---|---|---|
| 성능 | `TenantJdbcResourceRegistry.kt:307`의 `FailureAccumulator`가 identity index를 한 번 만들고 suppressed graph를 선형으로 합친다. 128 tenant·256 cleanup failure 회귀 테스트를 확인했다. | 독립 재검토 `APPROVE`, P0/P1/P2/P3 0. |
| 안정성 | `TenantJdbcResourceRegistry.kt:68-139`의 atomic close owner, completion latch, interrupt 복원, 역순 cleanup과 `:291-300`의 marker-only fatal state를 확인했다. | 독립 재검토 `PASS`, P0/P1/P2/P3 0. 15개 lifecycle/concurrency test를 직렬 실행했다. |
| 보안 | `TenantJdbcResourceRegistry.kt:35-58`의 explicit database 선택 경고, 고정 오류 메시지, fatal 원형 비보관과 README의 authorization-before-lookup을 대조했다. | 독립 재검토 `PASS`, P0/P1/P2/P3 0. raw fatal과 tenant key를 provider 상태·오류에 복제하지 않는다. |
| 운영 | `TenantJdbcResourceRegistry.kt:23-26`, `:109-139`, `:283-289`의 shutdown 책임, unregister-before-dispose, readiness 비보장을 확인했다. CI/Nightly H2 shard와 ABI 45/45도 대조했다. | 독립 `COMMENT`, P0/P1/P2/P3 0. GitHub CI·Full Nightly·Central 배포는 다음 gate다. |
| 개발/API | Kotlin generic/null 계약, Java null 방어, `registry::databaseFor` source compatibility, API baseline 23행과 Exposed 1.5.0 `Database.connect` 경계를 확인했다. | 독립 `COMMENT`, P0/P1/P2/P3 0. 27 tests와 `javap`, ABI baseline을 확인했다. |
| 사용자/호출자 | `README.ko.md:24-178`의 BOM, 인가 선행, `transaction(db = database)`, shutdown, failure, unsupported 범위를 영어 문서와 대조했다. | 독립 `COMMENT`, P0/P1/P2/P3 0. 양 locale heading 8/8과 code block parity를 확인했다. |

세 `COMMENT` lane에서는 `lsp_diagnostics`가 제공되지 않았고 운영 lane에서는
`ast_grep_search`도 제공되지 않았다. 컴파일, detekt, ABI, `javap`, 테스트, exact diff
검토로 보완했지만 이를 해당 도구가 실행된 것으로 기록하지 않는다.

## 발견 사항과 조치

| 심각도 | 발견 | 조치와 재검증 |
|---|---|---|
| P2, 해결 | `6124a7d4`의 failure aggregation이 suppressed 배열을 반복 조회해 failure 수가 커지면 O(F²)가 될 수 있었다. | `935d2844`에서 `IdentityHashMap` 기반 O(F) 합산으로 변경하고 128 tenant·256 failure identity/order 회귀 테스트를 추가했다. 성능 재검토와 27 tests 통과. |
| P1, 해결 | `935d2844`의 `ClosedFatal`이 raw fatal graph를 상태에 보관해 marker-only 설계와 달랐다. | `dafe7ddb`에서 `ClosedFatal`을 `data object`로 바꾸고 raw fatal은 owner-local로만 다시 던졌다. 안정성·보안 재검토 통과. |
| P2, 해결 | fatal unregister 테스트가 실제 `closeAndUnregister` 전에 실패해 Exposed 등록 해제를 증명하지 못했다. | 실제 unregister 뒤 fatal을 주입하고 `TransactionManager.managerFor(database)` 실패를 검증했다. 안정성 재검토 통과. |
| P2, 해결 | tenant lookup 뒤 인자 없는 `transaction {}`을 쓰면 Exposed의 global `primaryDatabase`가 선택될 위험이 문서에 충분히 드러나지 않았다. | KDoc과 영어·한국어 README에 `transaction(db = databaseFor(tenant))` 의무를 명시했다. 보안·호출자 재검토 통과. |

최종 구현에는 미해결 P0–P3가 없다. 성능 수치나 처리량 개선은 측정하지 않았으므로
주장하지 않는다.

## 통합 검토

주 세션은 독립 verdict를 받은 뒤 exact diff와 수용 기준을 다시 대조했다. 이 단계는
독립 리뷰가 아니라 **main integration review**다.

- 생성 시 tenant 입력을 먼저 materialize하고 null·duplicate를 factory 호출 전에
  거부한다. 동일 `DataSource` reference의 이중 소유도 거부한다.
- factory가 non-null `DataSource`를 반환한 순간 registry가 소유권을 인수한다.
  조립 실패와 종료 모두 현재·이전 resource를 생성 역순으로 정리한다.
- close owner는 한 번만 cleanup하고 동시 caller는 완료 결과를 관찰한다. non-fatal은
  같은 aggregate identity를 재노출하며 fatal 원형은 owner에게만 전달한다.
- lookup은 authorization이나 lease가 아니다. caller가 요청 차단·drain·timeout·
  readiness·credential·redacted telemetry를 소유한다.
- production runtime은 Exposed JDBC/Core 1.5.0만 확인되며 Spring, Ktor, HikariCP,
  Micrometer, Reactor가 유입되지 않았다. HikariCP와 H2는 test scope다.
- Ktor의 기존 tenant transaction adapter는 변경하지 않았다. method reference compile
  fixture만 공용 registry와의 source compatibility를 확인한다.

## 검증 요약

| 검증 | fresh 결과 |
|---|---|
| 신규 module | 27 tests, failures/errors/skipped 0; Kover XML 19,171 bytes, 13 classes |
| 기존 JDBC | 603 tests, failures/errors 0, 기존 skipped 20 |
| 통합 Gradle | tenant build, JDBC test, Ktor tenant-jdbc compile, ABI, 전체 detekt `BUILD SUCCESSFUL` |
| ABI | modules/baselines/actualDumps 45/45, orphan/empty 0 |
| publication | metadata 46 files·94 variants·1,084 dependencies, POM 46 files·13,431 dependencies·46 models, failures 0 |
| CI contract | Python 7 tests, live validator, `py_compile`, CI/Nightly YAML parse와 actionlint 통과 |
| 문서·diff | 영어·한국어 예제 동등성, `git diff --check` 통과 |

## Writer 검토

- [x] **SPW-01**: issue, 기준·구현 SHA, 독자, 포함·제외 범위를 고정했다.
- [x] **SPW-02**: 판정, 심각도, 위치, 조치, 재검증과 미실행 gate를 분리했다.
- [x] **SPW-03**: 독립 리뷰와 main integration review를 구분하고 기술 식별자를
  보존했다.
- [x] **SPW-04**: 테스트·ABI·publication·CI 수치를 실제 산출물과 대조했다.
- [x] **SPW-05**: 문서 read-back, locale parity, 링크·Markdown·용어 검사를 수행했다.
- [x] **KO-01~KO-07**: 주어와 책임 주체를 명시하고, 번역투·과장·모호한 대명사·
  불필요한 비유 없이 문장을 다듬었다. API 이름과 오류 메시지는 원문을 유지했다.

## DoD Status

- [x] 구현 exact diff의 여섯 관점 리뷰와 main integration review 완료.
- [x] 발견된 P1/P2를 수정하고 영향 관점 재검토 완료.
- [x] 최종 구현의 P0/P1/P2/P3 0 확인.
- [x] 로컬 테스트·ABI·publication·CI contract·문서 검증 완료.
- [ ] PR 생성과 exact-head GitHub CI 확인.
- [ ] Full Nightly 실행과 terminal job 확인.
- [ ] merge, release, downstream `exposed-workshop#269` 이전.

최종 상태: **로컬 리뷰 DONE, delivery PENDING**.
