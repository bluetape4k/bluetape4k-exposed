# ClickHouse JDBC V2 stacked train 수정 설계 리뷰

## DoD 상태

- 리뷰 대상: `docs/superpowers/specs/2026-09-09-clickhouse-v2-stacked-train-design.md`
- 기준: base commit `5080a753`, 수정본 blob SHA-256 `1c92b3c48928c04f09c2730c36edd0cf46832e6ebd5c2ca443d21fa145ff41d8`
- 리뷰 범위: #865 → #866 → #867 → #868의 설계 경계, API/JVM 호환성, 보안·redaction, 타입 의미론, 테스트·성능, 문서·운영
- 리뷰 방식: 독립 lane 실패 후 leader 세션에서 수행한 **비독립 inline fallback**
- 최종 결과: `PASS` (P0=0, P1=0, P2=0, P3=0)
- 설계 gate: 통과. 사용자 재승인·실행 계획 승인은 아직 필요하므로 전체 train 상태는 `PENDING`이다.

## 리뷰 전환 근거

최초 독립 리뷰는 P1 처분을 요청했고, 첫 수정본에 반영했다. 수정본 R2에서는 아키텍처 lane이 P0=0/P1=2로 다시 `BLOCK`을 반환했다. API/Kotlin lane과 테스트·성능·문서 lane은 사용량 제한으로 결과를 반환하지 못했다. 세 lane의 `lane-fail` receipt를 남긴 뒤, 독립 리뷰가 완료된 것으로 가장하지 않고 동일 여섯 관점을 leader가 다시 읽었다.

R2 P1 처분은 다음과 같다.

1. `beta.row_binary_for_simple_insert`를 connection-scoped로 고정하고 `ClickHouseConnectionProvider.open(rowBinaryEnabled: Boolean)`의 dual-profile, 검증, pool·transaction 소유권, provider 부재 시 `UnsupportedConfiguration`을 명시했다.
2. 인증을 `Basic | AccessToken | BearerToken` one-of로 단일화하고 token mode에서 basic credential을 전달하지 않도록 했으며, 새 options overload의 URL 인증 key를 거부했다.
3. cleanup failure를 `SanitizedCleanupException`으로 변환해 원본 `Throwable`을 exception graph에 연결하지 않도록 했다.
4. query id/log comment는 typed option만 허용하고, #867 전용 beta property는 #865 `rawProperties`에서 거부하도록 소유권을 단일화했다.

## 여섯 관점 결과

| 관점 | 결과 | 확인한 계약과 근거 |
|---|---|---|
| 아키텍처·소유권 | PASS | V2 `prepareStatement`의 connection-level statement 선택을 전제로, 적격 batch는 beta-enabled connection, 부적격·capability-unknown batch는 beta-disabled connection을 statement 생성 전에 선택한다. provider가 profile을 검증하고 executor는 operation-scoped connection·statement·result만 닫는다. 전송 후 fallback·resend·profile 혼합은 금지한다. |
| 보안·redaction | PASS | Basic/AccessToken/BearerToken one-of, token mode의 `http_use_basic_auth=false`와 placeholder credential 제거, auth URL/raw key 거부, typed-only query id/log comment, header allowlist·CR/LF 거부, scrubbed URL, sanitized cleanup exception, exception graph canary를 확인했다. |
| API·JVM 호환성 | PASS | 기존 overload와 `$default` bridge를 보존하고 options를 필수 trailing 인자로 둔다. 두 신규 descriptor, `@JvmName` 금지, URL/typed/raw precedence, Kotlin·Java fixture·`javap`·ABI 명령이 명시되어 있다. |
| Kotlin 타입 의미론 | PASS | container/element nullability를 분리하고 nested depth·empty metadata, Array/Map/Tuple/Nested/JSON/UUID/IP/DateTime/Decimal/Enum/UInt64의 입력·wire·overflow·rounding·timezone·precision·malformed 경계를 고정했다. H2와 ClickHouse 결과를 별도 증거로 둔다. |
| 테스트·성능 | PASS | DS-01~DS-09의 exact command·expected result·artifact·receipt schema, Testcontainers 직렬화와 PENDING 규칙, 36조합·3 process run·raw SHA·provenance·deterministic chart를 확인했다. private driver byte bound는 주장하지 않고 peak heap/RSS만 측정한다. |
| 문서·운영 | PASS | SPW-01~05, EN/KO README·KDoc, repo lesson, 중앙 매뉴얼 traceability, 인증/DNS/pool·malformed input·provider 부재·callback failure·partial acceptance 운영 지침과 `Closes #...` metadata를 확인했다. |

## 잔여 범위

다음 항목은 설계 결함이 아니라 구현 이후 검증할 `PENDING` 범위다.

- 신규 코드, 모듈 테스트, ABI 실행, benchmark raw/chart, hosted CI, PR·merge 증거는 아직 없다.
- JUnit lock과 `junit.jupiter.execution.parallel.enabled=false`는 구현 시 추가하고 DS-03/04/05에서 검증한다.
- 확장 자체가 생성하는 logger만 redaction 범위에 포함한다. ClickHouse driver 외부 logger의 출력 정책은 이 train의 보장으로 주장하지 않는다.
- deterministic socket timeout과 in-flight remote `KILL QUERY` 완료는 #863 범위다.

## SPW writer gate

| 항목 | 결과 | 증거 |
|---|---|---|
| SPW-01 | PASS | 설계 파일, 현재 ClickHouse source, ClickHouse JDBC V2 `0.9.9` source URL, 이슈 #865~#868, unsupported/후속 범위를 고정했다. |
| SPW-02 | PASS | 설계의 경계·대안·API·실패·호환성·수용 기준·PR train·복구 절이 모두 존재한다. |
| SPW-03 | PASS | Korean technical register와 API/command/token 보존을 확인했고 용어 audit 결과 `findings=[]`다. |
| SPW-04 | PASS | `ClickHouseDatabase.kt:80-155`, `ArrayColumnType.kt:19-54`, `build.gradle.kts:2-15`, V2 `ClientConfigProperties`, `ConnectionImpl`, `WriterStatementImpl`와 설계 주장을 대조했다. |
| SPW-05 | PASS | 최종 Markdown을 다시 읽고 표·코드 fence·경로·상태를 확인했다. `git diff --check`가 통과했다. |

### Korean naturalness checklist

| 항목 | 결과 | 확인 |
|---|---|---|
| KO-01 | PASS | source URL, issue 번호, API key, 명령, 수치, 미검증 범위를 그대로 보존했다. |
| KO-02 | PASS | 중요성·성능을 주장하는 표현 대신 profile 선택, redaction, receipt, 측정 경계를 적었다. |
| KO-03 | PASS | 번역투·기계적 전환·모호한 결론을 제거하고 설계 리뷰 문체로 정리했다. |
| KO-04 | PASS | `connection`, `profile`, `fallback`, `PENDING`, `Closes` 등 기술 용어와 code token을 일관되게 사용했다. |
| KO-05 | PASS | 비유·홍보 표현·불필요한 유머가 없다. |
| KO-06 | PASS | 제목·표·코드 fence·경로·링크·상태를 최종 read-back했다. |
| KO-07 | PASS | terminology audit 결과 두 파일 모두 `findings=[]`이며 의도하지 않은 충돌이 없다. |

## 검증 명령

```text
git diff --check                                      PASS
audit-korean-terms.mjs --json <design>               PASS (findings=[])
./gradlew :bluetape4k-exposed-clickhouse:detekt --dry-run ...      PASS (task graph resolved; execution intentionally skipped)
./gradlew :bluetape4k-exposed-clickhouse:checkKotlinAbi --dry-run ... PASS (task graph resolved; execution intentionally skipped)
/Users/debop/.local/bin/cairosvg --version            PASS (2.9.0)
```

이 문서는 설계 리뷰 receipt이며 구현·성능·CI 통과를 대체하지 않는다. 다음 단계는 이 수정 설계에 대한 사용자의 재승인 후 상세 실행 계획을 작성하고 별도 계획 리뷰를 통과하는 것이다.
