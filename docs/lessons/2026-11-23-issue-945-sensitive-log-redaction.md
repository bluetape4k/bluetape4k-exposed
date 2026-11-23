# Issue #945 민감 로그 회귀 교훈

## 맥락

종료된 [#611](https://github.com/bluetape4k/bluetape4k-exposed/issues/611)은 일반 엔티티와 식별자 원문을 로그에 남기지 않는 계약을 정했다. 2.2.0 개발선의 7-Tier 리뷰 [#941](https://github.com/bluetape4k/bluetape4k-exposed/issues/941)은 JDBC/R2DBC 캐시 어댑터와 Druid·Trino 경로에서 식별자, 키/값, 엔티티, SQL, JDBC URL, `Throwable`이 다시 로그에 포함되는 회귀를 찾았다. 수정 대상은 [#945](https://github.com/bluetape4k/bluetape4k-exposed/issues/945)이며, 이슈는 `2.2.0` milestone에서 열린 상태다.

주요 원인은 로그 출력 계약을 어댑터 구현과 테스트에 일관되게 고정하지 못한 점이다. Redisson loader 테스트는 원시 ID가 로그에 있어야 한다고 기존 비노출 검증을 뒤집었다. 확인된 경로를 고친 뒤 인접 구현을 다시 검색하자, 지원하지 않는 ID 타입을 처리하는 공용 core helper도 호출자 ID를 경고에 기록하고 있었다.

## 결정과 결과

운영 로그에서 호출자 소유 식별자·키/값·엔티티, 배치 원소, SQL, JDBC URL과 예외 message/stack trace를 제외한다. 진단에는 고정된 `operation` 이름, 원소 수, 안전한 타입 이름만 남긴다. 오류를 기록할 때 `Throwable`을 logger 인자로 전달하지 않는다.

수정은 아래 인접 경로와 회귀 테스트에 적용했다.

- JDBC Redisson loader/writer 및 R2DBC Redisson loader: 호출자 식별자와 예외 원문을 제외하고 로그 캡처 검증을 비노출 계약으로 복구했다.
- JDBC/R2DBC Lettuce map과 writer: 키/값·엔티티·배치 원문을 제거하고 `operation`·원소 수·오류 타입만 남긴다. write-behind 실패에서 키·값·exception message 및 `throwableProxy`가 없는지 테스트한다.
- JDBC Caffeine repository: 저장 완료 로그에서 식별자와 엔티티를 제거한다.
- 공용 Exposed core conversion helper: 지원하지 않는 ID 타입 경고에 ID 값 대신 입력/대상 타입 이름만 기록하고 KDoc에 경계를 적었다.
- Druid·Trino: SQL 원문과 credential이 포함될 수 있는 JDBC URL 대신 operation 이름을 기록하고 sentinel 테스트를 추가했다.

## 검증 결과

| 경로 | 검증 | 결과 |
|---|---|---:|
| Exposed core | `ExposedColumnSupportsTest` | 6 통과 |
| JDBC Redisson | `ExposedEntityMapLoaderTest`, `SuspendedExposedEntityMapLoaderTest` | 22 통과 |
| R2DBC Redisson | `R2dbcExposedEntityMapLoaderTest` | 8 통과 |
| JDBC Caffeine | `SensitiveLogRedactionTest` | 1 통과 |
| JDBC Lettuce | `SensitiveLogRedactionTest`, `ExposedEntityMapWriterTest`, `SuspendedExposedEntityMapWriterTest` | 21 통과 |
| R2DBC Lettuce | `ExposedR2dbcLettuceSuspendedLoadedMapTest` | 7 통과 |
| Druid | `DruidJdbcTest` | 4 통과 |
| Trino | `TrinoDatabaseValidationTest` | 8 통과 |

선택한 테스트는 총 77개 통과했다. 수정 전에는 Redisson ID 비노출 회귀와 core helper 회귀 테스트가 각각 sentinel 노출을 검출해 실패했다. 테스트 작성 중 JDBC Lettuce fixture의 제네릭 타입 추론 오류도 한 차례 수정했다. 최종 선택 테스트에는 skip이 없으며, GitHub CI와 전체 모듈 테스트는 실행하지 않았다. R2DBC Lettuce 실행에는 Netty native-access 경고가 있었지만 테스트는 통과했다.

독립 7-Tier 리뷰의 최종 verdict는 `APPROVE`이며 P0/P1 결함은 없었다. 첫 리뷰는 JDBC Lettuce 성공 경로의 빈 목록 `.all { ... }` 검사가 아무것도 증명하지 않는 점을 지적했다. 검사를 성공 경로에서 제거하고, 실패 이벤트가 실제로 3회 이상 발생할 때까지 기다린 뒤 key/value/message 비노출과 throwable 미첨부를 확인하도록 바꿨다. 리뷰가 지적한 오래된 주석의 `log.warn(e)` 구현도 삭제했다.

같은 리뷰는 범위 밖의 기존 `Throwable` 로깅 네 경로도 찾았다. 기존 이슈 검색에서 동일 경로 이슈를 찾지 못해 [#950](https://github.com/bluetape4k/bluetape4k-exposed/issues/950)을 새로 등록하고 #941, #611, #945에 연결했다. #950은 모든 예외에서 민감한 message가 노출된다고 단정하지 않고, 네 경로의 입력 추적과 sentinel 검증을 요구한다.

## 7-Tier 리뷰 결과

| 관점 | 근거와 상태 |
|---|---|
| 구조·모듈·의존성 | 의존성이나 모듈 경계는 바꾸지 않았다. `APPROVE`. |
| 보안·로그 | #945의 운영 로그 비노출 테스트와 운영 소스 검색을 확인했다. 나머지 네 `Throwable` 경로는 #950으로 분리했다. |
| 정확성·경계값 | 실패 경로에 sentinel ID/key/value/SQL/URL/message를 넣어 log-capture 결과를 확인했다. 선택 테스트 77개 통과. |
| lifecycle·동시성 | 실행/종료 동작을 바꾸지 않았다. Lettuce write-behind 실패 재시도 경로에서 redaction을 확인했다. |
| 성능·backpressure | 알고리즘, batch 크기, backpressure 동작은 바꾸지 않았다. 별도 benchmark는 실행하지 않았다. |
| API/ABI·문서 | 공개 선언은 변경하지 않았고 converter KDoc을 갱신했다. 별도 API/ABI 검사기는 실행하지 않았다. |
| 테스트·CI·toolchain | 선택 모듈 테스트만 실행했고 GitHub CI와 전체 모듈 테스트는 실행하지 않았다. Netty native-access 경고는 별도로 기록했다. |

## 재발 방지

1. 민감 값 노출을 요구하는 assertion을 redaction 테스트로 받아들이지 않는다. raw 값 부재와 예외 proxy 부재를 sentinel 기반으로 검증한다.
2. 예외 로그를 검증할 때는 먼저 대상 실패 이벤트가 실제 발생했는지 확인한다. 빈 목록에 대한 `.all` 결과만으로는 로그 경계를 증명할 수 없다.
3. Redisson 한 경로를 고친 뒤 JDBC/R2DBC, cache backend, 공용 helper, SQL·connection adapter의 sibling 로그를 검색한다.
4. source diff의 로그 변경과 운영 소스의 ID/key/value/entity/SQL/URL interpolation 및 `Throwable` 전달을 함께 검사한다. 주석 속 오래된 구현도 재사용될 수 있으므로 로그 감사에서 제거한다.
5. KDoc은 어떤 metadata를 기록할 수 있는지 구체적으로 설명하고, 테스트는 failure/retry 경로까지 해당 경계를 고정한다.

## Writer DoD

| Gate | 상태 | 근거 |
|---|---|---|
| SPW-01 근거 고정 | 완료 | 독자는 bluetape4k 유지보수자다. live #611/#941/#945, 현재 source diff와 선택 테스트 결과를 기준으로 썼다. |
| SPW-02 교훈 구조 | 완료 | 맥락, 원인, 결정, 결과, 검증, 실패 경로와 재발 방지 기준을 기록했다. |
| SPW-03 한국어 기술 문체 | 완료 | 아래 KO-01~KO-07을 마쳤고, Exposed 용어를 문맥에 맞춰 수동 검토했다. 용어 감사 결과는 findings 0건이다. |
| SPW-04 기술 의미·추적성 | 완료 | #611/#941/#945/#950과 현재 source/test 근거를 대조했다. 검증 공백도 구분했다. |
| SPW-05 최종 read-back | 완료 | 최종 Markdown, 링크, 표, 로그 검색 결과와 `git diff --check`를 다시 읽었다. 선택 테스트 외 검증 경계는 아래에 남겼다. |

### 한국어 문장·용어 점검

| 항목 | 상태 | 근거 |
|---|---|---|
| KO-01 의미 근거 | 완료 | 이슈 번호·상태, 테스트 이름·개수, 리뷰 verdict, 미실행 검증을 현재 작업 근거와 대조했다. |
| KO-02 막연한 주장 | 완료 | 효용을 과장하지 않고 로그에 남기는 값과 테스트 관찰 결과를 구체적으로 적었다. |
| KO-03 번역투·나열 | 완료 | 어댑터·fixture 등 일반 설명을 한국어로 정리하고, 원인·결정·결과 순서로 문장을 검토했다. |
| KO-04 용어·문체 | 완료 | `ID`, `operation`, `Throwable`은 코드·로그 계약을 가리킬 때만 유지하고, `key/value`, `entity` 등은 키/값, 엔티티로 적었다. |
| KO-05 유머·목소리 | 완료 | 비유나 홍보 표현 없이 엔지니어링 회고 문체를 유지했다. |
| KO-06 독자 표면 | 완료 | 본문·표·이슈 링크를 확인했다. 단일 한국어 lesson이며 짝 영문 locale 파일은 없다. |
| KO-07 용어 감사 | 완료 | `audit-korean-terms.mjs --json` 결과 `findings: []`; 기본 `clinic-appointment` 규칙 외에 도메인 규칙이 없어 Exposed 용어는 수동 검토했다. |

## 남은 검증 경계

- 이 worktree는 아직 커밋되지 않았으며 exact-head GitHub CI, API/ABI 검사와 전체 모듈 테스트는 수행하지 않았다.
- PR·merge를 하지 않았으므로 #945와 새 후속 이슈 #950은 모두 열린 상태다.
