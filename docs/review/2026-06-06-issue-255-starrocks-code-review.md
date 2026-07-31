# Issue #255 StarRocks 코드 검토

날짜: 2026-06-06
범위: `exposed/exposed-starrocks`, 루트 README 로케일 세트, 모듈 등록, CI/Nightly 워크플로 및 이슈 #255 설계 산출물.
게이트: 6-R 단계 구현 차이 검토

## 검토 입력 자료

- `bluetape4k-full-feature/references/step-6r-code-review.md`
- `bluetape4k-full-feature/references/step-4p-perf-scan.md`
- `bluetape4k-code-patterns/SKILL.md`
- 2-R 단계 명세 검토 판정: `P0=0`, `P1=0`
- 3-R 단계 계획 검토 판정: `P0=0`, `P1=0`

## 게이트 판정

- P0=0
- P1=0
- P2=0
- P3=0
- 게이트: 통과

## 반복 검토 기록

| 반복 | 검토 결과 | 심각도 | 조치 |
|---|---|---:|---|
| 1 | `StarRocksTableTest`의 테스트 이름은 `SchemaUtils`를 사용한다고 했지만 실제 테스트는 생성된 DDL을 의도적으로 직접 실행했다. 또한 `AbstractStarRocksTest`에 사용하지 않는 `dropEventsTableWithExposed` 도우미가 남아 있었다. | P3 | 수정: 실제 단언 경로에 맞게 테스트 이름을 변경하고 사용하지 않는 도우미/임포트를 제거했다. 테스트 소스를 다시 컴파일했다. |

## 7단계 검토

| 단계 | 결과 | 근거 |
|---|---|---|
| 보안 | 통과 | 연결 입력에 bluetape4k 검증 도우미를 사용한다. 비밀 정보나 외부 자격 증명을 커밋하지 않았다. 새 의존성/라이선스 근거를 명세와 PR에 기록했다. |
| 운영/SRE 신뢰성 | 통과 | 테스트 전에 StarRocks 준비 상태와 용량을 폴링한다. 원시 JDBC 연결은 `use`로 닫는다. 래퍼 구성 실패 시 원시 연결을 닫고 종료 실패를 억제한다. |
| 구조적 영향 | 통과 | 새 모듈은 `exposed/exposed-starrocks` 아래에 격리되어 있다. `./gradlew projects`에 `:bluetape4k-exposed-starrocks`가 표시된다. 기존 비 BOM 하위 프로젝트 규칙이 BOM 제약 조건을 자동 수집한다. |
| Kotlin/API 품질 | 통과 | 공개 API KDoc은 영어다. 연결 검증에 `requireNotBlank`/`requireInRange`를 사용한다. `!!`, `@Synchronized`, `GlobalScope` 또는 프로덕션 차단 코루틴 경로를 도입하지 않았다. |
| 테스트/타입/무응답 실패 | 통과 | 방언 등록, URL 검증, 옵션 검증, 명시적 DB 부트스트랩, `SELECT 1`, DDL 렌더링, 테이블 생성/삭제, 삽입/조회, `DatabaseMetaData`를 통한 테이블/열 탐색을 테스트한다. |
| 성능/안정성 | 통과 | 6단계 검사에서는 Testcontainers 설정의 의도적인 차단 준비 상태 폴링과 suspend가 아닌 `runCatching` 리소스 정리만 발견되었다. 핫 패스, 무제한 프로덕션 재시도, 리소스 누수 경로는 발견되지 않았다. |
| 문서화/릴리스 준비 상태 | 통과 | 루트 및 모듈 README 로케일 파일을 갱신했다. CI와 Nightly의 경로/작업/산출물/상태 요구 사항에 StarRocks를 포함했다. `actionlint`, `dependencyInsight`, Kover XML, `git diff --check` 근거가 있다. |

## 다이어그램 검토 부록

PR 생성 후 StarRocks 모듈 README에 아키텍처 및 로컬 스모크 수명 주기 다이어그램이
누락된 것을 확인했다. 이제 README 로케일 세트에는 영어 레이블을 사용하는 공유 PNG
애셋만 임베드한다.

- `docs/images/readme-diagrams/exposed-starrocks-diagram-01.png`
- `docs/images/readme-diagrams/exposed-starrocks-flow-02.png`

README PNG 옆에 대응하는 SVG 원본과 렌더링된 PNG 애셋을 추가했다. 이후 다이어그램
검증에서도 SVG/XML 파싱, CairoSVG 렌더링, 기하 검사, 시각 검사를 유지해야 한다.

| 다이어그램 게이트 | 결과 | 근거 |
|---|---|---|
| SVG/XML 파싱 | 통과 | 최종 SVG 애셋에 `xmllint --noout`을 실행했다. |
| PNG/SVG 애셋 쌍 | 통과 | README PNG 2개 모두 대응하는 SVG 원본이 있다. |
| README 임베드 규칙 | 통과 | `exposed-starrocks` README 파일은 PNG만 임베드하며 SVG 임베드는 발견되지 않았다. |
| 오래된 글꼴/화살표 패턴 검사 | 통과 | StarRocks SVG 애셋에서 `Inter`, `Arial`, `Helvetica`, `13x13`, `3.9x3.9` 마커 패턴이 발견되지 않았다. |
| 기하 요약 | 통과 | 아키텍처: `nodes=18`, `routes=11`, `segments=24`, `badEndpointAngle=0`, `badBends=0`, `interiorCrossings=0`, `marginImbalance=0`, `titleGap=PASS`; 시퀀스: `nodes=17`, `routes=9`, `segments=9`, 모든 오류 개수 `0`, `titleGap=PASS`. |
| 시각 검사 | 통과 | 렌더링된 PNG를 각각 검사했으며, 최초 아키텍처 경로 교차 문제를 커밋 전에 수정했다. |

## 검증 근거

| 명령 | 결과 |
|---|---|
| `./gradlew projects --no-configuration-cache --no-daemon` | 통과. 프로젝트 목록에 `:bluetape4k-exposed-starrocks`가 포함된다. |
| `./gradlew :bluetape4k-exposed-starrocks:dependencyInsight --dependency starrocks-connector-j --configuration runtimeClasspath --no-configuration-cache --no-daemon` | 통과. `com.starrocks:starrocks-connector-j:1.1.1`이 선택되었다. |
| `./gradlew :bluetape4k-exposed-starrocks:compileKotlin --no-configuration-cache --no-daemon` | 통과. |
| `./gradlew :bluetape4k-exposed-starrocks:cleanTest :bluetape4k-exposed-starrocks:test --no-build-cache --no-configuration-cache --no-daemon` | StarRocks 용량 준비 상태 프로브를 추가한 후 통과. 테스트 21개가 통과했다. |
| `./gradlew :bluetape4k-exposed-starrocks:koverXmlReport --no-configuration-cache --no-daemon` | 통과. |
| `./gradlew :bluetape4k-exposed-starrocks:compileTestKotlin --no-configuration-cache --no-daemon` | 6-R 단계 정리 패치 후 통과. |
| `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml` | 통과. |
| `git diff --check` | 통과. |
| 다이어그램 애셋 검사 | 통과: XML 파싱, PNG/SVG 애셋 쌍, README의 PNG 전용 임베드, 오래된 글꼴/마커 검사, 기하 요약, 개별 PNG 시각 검사를 수행했다. |

이 세션에서는 IntelliJ MCP 진단을 사용할 수 없어 Gradle 컴파일/테스트/Kover와 정적 워크플로 검사를 기록된 대체 검증으로 사용했다.

## 6-R 단계 체크리스트 완료 보고

| 항목 | 상태 | 비고 |
|---|---|---|
| 필수 참고 자료 로드 | 완료 | 판정 전에 6-R 단계, 4-P 단계/6단계, `bluetape4k-code-patterns`를 읽었다. |
| 7단계 검토 완료 | 완료 | 보안, SRE, 구조, Kotlin/API, 테스트, 성능/안정성, 문서/릴리스를 검토했다. |
| P0/P1 정규화 | 완료 | 검토 후 P0/P1 결과가 남아 있지 않다. |
| 비차단 검토 결과 처리 | 완료 | P3 테스트 근거 불일치 1건을 즉시 수정했다. |
| 수정 후 검증 갱신 | 완료 | P3 수정 후 `compileTestKotlin`이 통과했다. |
| P0=0/P1=0 종료 조건 | 완료 | 최신 통합 판정: `P0=0`, `P1=0`. |
| 다음 단계 차단 해제 | 완료 | 커밋 및 PR 생성을 진행할 수 있다. |
