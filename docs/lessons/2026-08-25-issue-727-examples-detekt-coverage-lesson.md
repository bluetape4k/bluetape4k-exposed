# 이슈 #727 examples 정적검사 범위 확대 lesson

## 배경

root `build.gradle.kts`가 examples 입력을 `exclude("**")`로 제거하고 있어, 6개 예제의 `detekt`가 성공해도 실제 분석은 `NO-SOURCE`였다. 따라서 nightly의 green 결과와 XML artifact가 예제 품질을 증명하지 못했다.

## 결정

1. `/examples/` blanket exclusion을 제거하고 generated source만 공통 제외한다.
2. root `exampleDetekt`가 6개 examples subproject의 Detekt task와 non-empty XML report를 fail-closed로 확인한다.
3. Kotlin source guard가 production `println`/`System.out`/`System.err`/`!!`, Ktor direct `UUID.randomUUID`, test raw JUnit/kotlin.test assertion을 검출한다.
4. 기존 Bluetape helper를 우선한다. assertion은 `bluetape4k-assertions`, ID는 `Uuid.V7`, 운영 출력은 `KLogging`을 사용한다.
5. DDD Modulith UUID 경로는 선행 이슈 #726/#741과 중복하지 않고 Ktor scope로 분리한다.

## 결과

- RED에서 Detekt finding 43건과 raw pattern 25건을 확인했다.
- GREEN에서 `exampleDetekt`가 6개 프로젝트의 non-empty XML을 확인하고 `Example pattern rules passed: production=36, tests=18`을 출력했다.
- root `detekt` 44 tasks가 성공했다.
- 임시 `PatternGuardProbe.kt`에 `println`을 주입한 negative run은 `production println` 위치를 보고하며 실패했고, probe 제거 후 GREEN을 다시 확인했다.
- Ktor unit 10, DDD 10, JDBC 27, R2DBC 36, ClickHouse Testcontainers 1, PostgreSQL Testcontainers 4 tests가 성공했다.
- Ktor diagnostic sink의 `println`을 제거하고 allowlisted structured fields만 logger로 남겼다. 이 변경은 사용자의 `println` 지적을 직접 반영한다.

## 놓치기 쉬운 점

- Detekt 저장소 규칙은 `RuntimeException`도 generic으로 판정했다. 모든 예외를 수집해야 하는 lifecycle/adapter 경계는 함수 단위 `TooGenericExceptionCaught` suppression과 근거 주석을 사용하고, 나머지는 더 좁은 타입으로 유지해야 한다.
- `Uuid`의 실제 패키지는 `io.bluetape4k.idgenerators.uuid.Uuid`다. compileKotlin이 이 import 오류와 route receiver 오류를 즉시 드러냈고, 수정 후 compile/test를 다시 실행했다.
- `LocalCacheConfig.maximumSize`는 `Long` 계약이므로 named constant도 `1_000L`이어야 한다.

## 재발 방지 guard

- `exampleDetekt`를 nightly static-analysis에 직접 연결하고, analyzed project 및 pattern-pass 로그를 `grep`한다.
- 예제 task가 다시 `NO-SOURCE`가 되거나 report가 비어 있으면 aggregate가 실패하도록 유지한다.
- 새 example production code에서 `println`, `System.out`, `System.err`, `!!`, 직접 UUID 생성이 들어오면 root pattern guard가 실패한다.
- raw assertion 허용이 필요하면 파일/규칙 단위 근거와 이슈를 함께 남긴다. blanket exclusion은 복원하지 않는다.
- #726/#741 병합 이후 DDD UUID ownership과 Ktor guard scope가 어긋나지 않는지 다음 PR에서 재확인한다.

## 문서 검증

- SPW-01~SPW-05: PASS — 이슈/기준 커밋/설계/계획/명령 결과를 read-back했다.
- KO-01~KO-07: PASS — Korean naturalness checklist와 terminology audit 결과 findings 0.
