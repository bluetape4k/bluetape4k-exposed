# #942 Exposed 1.6.1 R2DBC DAO 연동 설계

## 문서 상태

- 저장소: `bluetape4k-exposed`
- 이슈: [#942](https://github.com/bluetape4k/bluetape4k-exposed/issues/942)
- 설계 승인: 사용자가 `exposed/r2dbc-dao` 별도 모듈 방향을 승인함
- catalog 선행 이슈: [bluetape4k-dependencies#293](https://github.com/bluetape4k/bluetape4k-dependencies/issues/293), `backlog`
- 구현 승인: 별도 구현 계획의 승인이 필요함
- 기준 ref: `develop` / `cd4eb89dd1f5240560327a9f0902668fc51c9286`
- 작업 branch: `feat/issue-942-r2dbc-dao`

## 문제

Exposed 1.6.0에서 실험적 R2DBC DAO 모듈 `exposed-dao-r2dbc`가 추가됐고, 현재 중앙 Exposed 버전은 1.6.1이다. 저장소에는 이 아티팩트를 사용하는 소비자 경로가 없다. 기존 JDBC DAO 모듈과 현재 R2DBC 모듈을 같은 소비자 클래스패스에 함께 넣으면 JDBC DAO가 유입될 수 있다.

이 작업은 Exposed가 제공하는 실험 API를 별도 Bluetape4k 아티팩트에서 재현 가능하게 소비하고, JDBC DAO와의 의존성 격리를 게시 좌표 기준으로 검증한다. Exposed DAO 런타임을 다시 구현하거나 실험 API를 안정 API로 포장하지 않는다.

## 근거와 현재 상태

### Exposed 원본 계약

- [Exposed 모듈 문서](https://www.jetbrains.com/help/exposed/adding-dependencies.html#modules)는 `exposed-dao-r2dbc`를 실험적 미리보기로 표시하고 `exposed-r2dbc`에 의존하도록 안내한다. JDBC DAO와 R2DBC DAO를 한 소스 세트에 함께 둘 수 없으며 `Entity`, `EntityClass`, `EntityCache` 등 같은 단순 이름의 타입이 충돌한다.
- [JDBC DAO에서 R2DBC DAO로의 마이그레이션 문서](https://www.jetbrains.com/help/exposed/migrating-from-jdbc-to-r2dbc-dao.html)는 `newSuspend`, Flow 기반 관계, `attach()` 및 명시적 experimental opt-in의 차이를 설명한다.
- [Exposed 1.6.0 변경 사항](https://www.jetbrains.com/help/exposed/whats-new-160.html#r2dbc-dao-support)은 R2DBC DAO가 1.6.0에 실험 기능으로 추가됐음을 기록한다.
- [Exposed 1.6.1 원본 빌드](https://github.com/JetBrains/Exposed/blob/73ddea063d539e4f98f01c5130b0bbcfe694f925/exposed-dao-r2dbc/build.gradle.kts#L19-L27)는 DAO 모듈이 `exposed-core`와 `exposed-r2dbc`를 API 의존성으로 두고 `ExperimentalR2dbcDaoApi` opt-in을 사용함을 보여준다.
- [Exposed 1.6.1 experimental opt-in 선언](https://github.com/JetBrains/Exposed/blob/73ddea063d539e4f98f01c5130b0bbcfe694f925/exposed-dao-r2dbc/src/main/kotlin/org/jetbrains/exposed/v1/dao/r2dbc/ExperimentalR2dbcDaoApi.kt#L5-L17)은 비호환 변경이 patch release에도 생길 수 있다고 경고한다.

### 저장소 계약

- `settings.gradle.kts:170-174`는 `exposed/*` 하위 디렉터리를 `bluetape4k-exposed-*` Gradle 모듈 이름으로 자동 등록한다. 새 디렉터리는 `:bluetape4k-exposed-r2dbc-dao`가 된다.
- `exposed/r2dbc/build.gradle.kts`는 `:bluetape4k-exposed-dao`를 `api`로 노출한다. 따라서 새 모듈은 `:bluetape4k-exposed-r2dbc`에 의존하지 않는다.
- `exposed/core/build.gradle.kts`에서 DAO는 테스트 의존성으로만 포함된다. 새 모듈은 `:bluetape4k-exposed-core`의 공개 의존성만 재사용할 수 있다.
- `gradle/libs.versions.toml`에는 `exposed-dao`와 `exposed-r2dbc` alias가 있지만 `exposed-dao-r2dbc` alias는 없다. 중앙 catalog의 현재 Exposed 버전 ref는 `1.6.1`이다.
- `exposed/bom`은 게시 대상 하위 프로젝트를 자동으로 포함한다. 루트 ABI 목록은 게시 대상 JVM 모듈 수를 45개로 고정하고, 각 모듈에 비어 있지 않은 ABI baseline과 dump를 요구한다. Kotlin 공개 API가 없는 의존성 재노출 전용 모듈은 명시적 allowlist와 빈 ABI 증거 계약 없이 모듈 수만 늘리면 검증을 통과하지 못한다.
- `.github/workflows/ci.yml`은 R2DBC H2 테스트, 경로 필터, 필수 `ci-status` 작업과 테스트/Kover 산출물 경로를 명시한다. 새 모듈 경로를 감지하고 실제 테스트를 실행하도록 연결해야 한다. 프로덕션 Kotlin 소스가 없는 의존성 재노출 전용 모듈에 Kover를 적용할 수 있는지 확인하고, 적용할 수 없다면 N/A 근거를 기록한다.
- `utils/batch/consumer-fixtures/r2dbc-jackson3-runtime/`는 게시 좌표를 사용하는 외부 소비자 fixture의 선례다. 모듈 내부 테스트만으로는 게시 POM의 JDBC DAO 격리를 입증하지 못한다.
- 루트 `scripts/publication/validate_downstream_consumer.rb`는 임시 Maven 저장소에 게시한 뒤 Java 소비자와 BOM 경로를 검증한다. Kotlin experimental opt-in과 JDBC DAO 격리 검사가 포함된 R2DBC DAO fixture는 별도 검증 경로가 필요하다.

## 설계 선택

### 대안

| 대안 | 장점 | 제약 | 판정 |
|---|---|---|---|
| 기존 `exposed/r2dbc`에 R2DBC DAO를 추가 | 별도 아티팩트가 필요 없다 | 현재 모듈이 JDBC DAO를 `api`로 노출하므로 Exposed가 경고한 소스 세트 충돌 조건을 위반한다 | 제외 |
| `exposed/r2dbc-dao`에 실험 DAO를 재노출하고 문서·테스트를 제공 | JDBC DAO와 독립된 게시 좌표, 얇은 의존 경계, Exposed 원본 API 변경을 새 Bluetape API로 고정하지 않음 | 소비자는 Exposed 실험 API opt-in을 직접 수행해야 함 | **선택** |
| JDBC DAO의 엔티티·저장소 추상화를 전부 복제해 R2DBC API를 제공 | 기존 사용 모양과 유사함 | patch release에도 바뀔 수 있는 Exposed 원본 API를 광범위한 공개 API로 복제하고 호환성 책임을 늘림 | 첫 버전에서는 제외 |

### 모듈 및 의존성 경계

1. 새 모듈 경로는 `exposed/r2dbc-dao`, 게시 모듈 이름은 `bluetape4k-exposed-r2dbc-dao`로 한다.
2. 모듈은 중앙 Exposed catalog에서 `exposed-dao-r2dbc`를 `api`로 노출하고, 필요한 Bluetape4k 공통 API로 `:bluetape4k-exposed-core`만 재사용한다.
3. 새 모듈은 `:bluetape4k-exposed-r2dbc`, `:bluetape4k-exposed-dao`, JDBC DAO alias를 의존하지 않는다. Exposed DAO 아티팩트가 API 의존성으로 `exposed-core`와 `exposed-r2dbc`를 노출하므로 별도 wrapper 의존성은 추가하지 않는다.
4. 중앙 catalog 선행 이슈 [bluetape4k-dependencies#293](https://github.com/bluetape4k/bluetape4k-dependencies/issues/293)는 `exposed-dao-r2dbc` alias를 기존 `exposed = 1.6.1` version ref에 연결한다. 새 version key나 버전 변경은 포함하지 않는다. 해당 catalog alias가 소비 저장소에서 검증되기 전에는 이 모듈의 구현·해결을 진행하지 않는다.
5. 첫 버전은 별도 Kotlin DAO wrapper 또는 안정된 Bluetape4k DAO 계약을 추가하지 않는다. 문서와 소비자 fixture가 Exposed 원본 API의 사용법·opt-in·제약을 드러내고, 새 추상화는 후속 요구에 따라 별도 설계로 다룬다.
6. 새 아티팩트는 의존성 재노출 전용 모듈로 등록한다. ABI 목록에는 `dependencyOnlyModules` 허용 목록을 두고 이 모듈에만 빈 baseline 파일과 빈 실제 ABI dump를 허용한다. 파일 누락, 허용 목록 밖의 빈 ABI, 허용 모듈에 공개 ABI가 추가되는 경우는 실패해야 한다. validator helper 단위 테스트와 게시 JAR에 자체 `.class` 항목이 없는지 확인해 이 경계를 고정한다.

### 지원하는 모듈 조합

| 소비자 구성 | 상태 | 이유 |
|---|---|---|
| `bluetape4k-exposed-r2dbc-dao` + `bluetape4k-exposed-core` + Exposed `exposed-dao-r2dbc` | 지원 | JDBC DAO를 노출하는 기존 Bluetape4k R2DBC 모듈을 거치지 않는 독립 경로다 |
| `bluetape4k-exposed-r2dbc-dao` + `bluetape4k-exposed-r2dbc` | 지원하지 않음 | 기존 `bluetape4k-exposed-r2dbc`가 `bluetape4k-exposed-dao`를 API로 노출해 Exposed의 JDBC/R2DBC DAO 이름 충돌 조건을 위반한다 |
| JDBC DAO와 Exposed R2DBC DAO를 같은 소스 세트에 추가 | 지원하지 않음 | Exposed가 같은 단순 이름의 타입 충돌을 명시적으로 경고한다 |

기존 R2DBC repository helper와 R2DBC DAO를 한 소비자 경로로 묶는 모듈 구조는 이 이슈에서 추가하지 않는다. 이 P2는 7-Tier 검토 epic [#941](https://github.com/bluetape4k/bluetape4k-exposed/issues/941)에서 발견해 후속 설계 이슈 [#954](https://github.com/bluetape4k/bluetape4k-exposed/issues/954)로 분리했다. 해당 제약은 #942 구현을 막지 않는다.

### 성능 범위

새 아티팩트는 Exposed 원본 API를 재노출하는 의존성 전용 모듈이므로 자체 실행 경로나 처리량·지연시간 SLA, 별도 성능 측정을 추가하지 않는다. `newSuspend`의 즉시 INSERT와 관계 Flow의 지연 수집이 만드는 Exposed 원본의 비용과 실행 시점을 바꾸지 않으며, 계약 테스트로 이 동작을 고정한다.

### 트랜잭션과 자원 소유권

- 호출자는 `suspendTransaction()`과 `R2dbcDatabase`, 커넥션 풀, 드라이버 및 종료 책임을 소유한다.
- 모듈은 `Database`, 커넥션 풀, 디스패처, 커넥션 또는 자동 종료를 만들거나 전역 등록하지 않는다.
- DAO 작업과 관계 Flow의 최종 수집은 활성 `suspendTransaction()` 안에서 완료한다. Flow를 트랜잭션 밖으로 반환해 수집하는 사용법은 지원하지 않는다.
- 테스트는 `toList()`와 `count()`를 활성 트랜잭션 안에서 수집하고, Flow 최종 수집이 트랜잭션 경계 안에서 일어나는지 확인한다. 트랜잭션이 닫힌 뒤 Flow를 수집하는 동작은 지원 계약으로 삼지 않는다.
- 취소 검증은 관계 Flow를 실제로 구독하고 커넥션 풀에서 커넥션을 획득한 뒤 statement 구독을 확인한다. 첫 행을 처리할 때 도달하는 동기화 지점에서 취소하고 `CancellationException` 전파, 미커밋 행의 롤백, 커넥션 획득·반환 기록, 최대 연결 수가 1인 호출자 소유 풀에서 후속 트랜잭션 성공, `finally`에서 fixture와 풀 정리를 확인한다.
- H2 취소 회귀는 PR 필수 경로에 두고 PostgreSQL 검증은 기존 R2DBC 야간 CI에 연결한다. DB별 테스트는 순차 실행한다.
- 두 개의 독립 `suspendTransaction()`을 동기화 지점에서 겹쳐 실행하는 H2 경계 테스트로 트랜잭션 context와 DAO 상태가 섞이지 않는지 확인한다. 같은 검증은 PostgreSQL 야간 CI에도 연결한다. 자체 트랜잭션 context registry를 만들지 않으므로 더 넓은 stress 성능 측정은 범위 밖이다.
- 모듈은 상태 확인·준비 상태 검사, 메트릭, 로그 정책 또는 자원 종료를 소유하지 않는다. 소비자는 취소·롤백, `EntityNotFoundException`, dirty-cache `IllegalStateException`을 관찰하고 외부 경계에서 처리한다.

## 실패 모드와 대응

| 실패 모드 | 관찰 가능한 신호 | 대응 및 회귀 방지 |
|---|---|---|
| 소비자 클래스패스에 JDBC DAO가 함께 들어옴 | 해석된 의존성 그래프에 `org.jetbrains.exposed:exposed-dao` 또는 Bluetape4k JDBC DAO가 존재 | 격리된 소비자 검증 fixture에서 두 아티팩트의 존재 여부를 확인하고 모듈 의존성 그래프를 검토한다 |
| patch release에서 experimental DAO API가 비호환 변경됨 | Exposed 1.6.1 고정 API 컴파일 또는 계약 테스트가 실패함 | 지원 Exposed 버전을 명시하고, 상향은 별도 호환성 검토와 같은 계약 테스트를 통과할 때만 수행한다 |
| `new()` 직후 DB가 생성한 ID를 읽거나 Flow 관계를 수집하지 않음 | ID가 아직 없거나 결과 누락·지연 | `newSuspend` 반환 시점의 ID 계약과 Flow 최종 수집을 테스트·문서화한다 |
| 트랜잭션 밖에서 관계 Flow를 수집하거나 취소 뒤 커넥션이 반환되지 않음 | 트랜잭션/컨텍스트 오류, 후속 질의 시간 초과, 미커밋 행 잔존 | Flow 최종 수집을 트랜잭션 안으로 제한하고 H2 단일 커넥션 풀에서 취소 뒤 후속 질의와 롤백을 확인한다 |
| 다른 트랜잭션의 entity를 재사용하거나 행이 삭제됨 | `EntityNotFoundException`, 수정된 cache 인스턴스의 `IllegalStateException` | 트랜잭션 간 `attach()` 성공, 삭제 행 예외, 수정된 cache 충돌, `force = true` 뒤 DB 값 재적용과 기존 변경 값이 후속 트랜잭션에서 저장되지 않음을 각각 확인한다 |
| 예외·취소 뒤 트랜잭션이 커밋되거나 취소가 삼켜짐 | 행이 남거나 `CancellationException`이 반환되지 않음 | 고정 동기화 지점에서 취소해 롤백·전파를 확인하고 풀 재사용으로 커넥션 반환을 입증한다. 자원은 호출자 소유 계약으로 유지한다 |
| 새 모듈이 CI·ABI 목록에서 빠짐 | 모듈 수 불일치, 경로 변경으로 테스트 미실행 | ABI baseline, 예상 모듈 수, CI 경로와 Kover 적용 여부를 같은 변경에서 갱신한다 |
| 중앙 `exposed` version ref가 바뀌어 지원선이 달라짐 | 소비자 그래프에서 선택한 버전이 1.6.1과 달라짐 | 불변 catalog ref/checksum과 선택 아티팩트 버전을 기록하고 ref 변경 시 같은 계약 검증을 다시 실행한다 |
| 예제나 진단에 접속 정보가 노출됨 | URL, 인증 정보, 원문 SQL 또는 tenant ID가 문서·로그에 나타남 | 예제는 환경 변수 placeholder만 사용하고 새 모듈/fixture의 로그와 diff에서 비밀 정보 노출 여부를 확인한다 |

## 검증 계약과 수용 기준

1. 중앙 version catalog의 alias `exposed-dao-r2dbc`가 기존 `exposed` version ref를 쓰고, 고정된 catalog SHA/checksum에서 `org.jetbrains.exposed:exposed-dao-r2dbc:1.6.1`을 resolve한다. 소비자 검증은 catalog ref/checksum과 선택 버전을 기록하고 다른 의존성 출처가 없는지 확인한다.
2. `:bluetape4k-exposed-r2dbc-dao`가 `exposed-core`와 Exposed R2DBC DAO를 API로 노출하고 `:bluetape4k-exposed-r2dbc`, `:bluetape4k-exposed-dao`, Exposed JDBC DAO를 의존하지 않는다. 게시 POM과 격리된 의존성 그래프에서 이 경계를 확인한다.
3. H2 계약 테스트가 `newSuspend`의 즉시 생성 ID와 관계 Flow의 `toList()`/`count()`를 활성 `suspendTransaction()` 안에서 검증한다. Flow를 트랜잭션 밖에서 수집하는 행위는 지원하지 않으며 이를 전제로 한 결과를 공개하지 않는다.
4. H2 계약 테스트가 별도 트랜잭션의 `attach()` 성공, 삭제 행의 `EntityNotFoundException`, 수정된 cache 인스턴스의 `IllegalStateException`, `force = true` 뒤 DB 상태 재적용과 교체된 미저장 값이 후속 트랜잭션에서 저장되지 않음을 각각 확인한다.
5. 두 개의 독립 트랜잭션을 겹쳐 실행하는 H2 테스트가 트랜잭션 컨텍스트와 DAO 상태 분리 및 커밋 결과를 확인한다. 풀에서 커넥션을 얻고 relation Flow의 statement를 구독한 뒤 첫 행 수집에서 도달하는 동기화 지점으로 취소하는 H2 테스트는 취소 전파, 미커밋 행의 롤백, 커넥션 획득·반환 이벤트, 단일 커넥션 풀에서 후속 질의 성공, 호출자 소유 풀·fixture 정리를 확인한다. PostgreSQL 야간 CI에서 두 경로를 모두 검증한다.
6. 임시 Maven 저장소에 게시한 좌표를 사용하는 Kotlin 소비자 fixture가 `ExperimentalR2dbcDaoApi` opt-in과 DAO 경로를 컴파일·실행한다. opt-in 없는 별도 컴파일 검증은 실험 API 진단으로 실패해야 한다. fixture 의존성 그래프에는 Exposed JDBC DAO와 Bluetape4k JDBC DAO가 없어야 한다.
7. 게시 대상 JVM 모듈 총수를 46으로 갱신한다. 새 의존성 재노출 전용 모듈만 ABI 허용 목록에 추가하고 존재하는 빈 baseline/dump를 검증한다. 파일 누락, 허용 목록 밖의 빈 ABI, 새 모듈의 비어 있지 않은 공개 ABI, 게시 JAR의 자체 `.class` 항목은 실패해야 한다. `buildSrc` validator 테스트로 경계를 고정한다.
8. `.github/workflows/ci.yml`의 `changes` 경로 필터가 `exposed/r2dbc-dao/**`, `gradle/libs.versions.toml`, 루트 ABI helper/test 경로를 감지한다. 필수 `test-r2dbc-h2` 작업이 `:bluetape4k-exposed-r2dbc-dao:test`를 실행하고 `ci-status`가 그 결과를 요구한다. `test-results-r2dbc-dao` 산출물은 `exposed/r2dbc-dao/build/test-results/test/*.xml`을 보존한다. 프로덕션 Kotlin 소스가 없는 이 모듈의 Kover는 N/A로 기록하고, 프로덕션 소스를 추가할 때는 별도 커버리지 계약이 필요하다.
9. 모듈 README EN/KR은 중앙 Bluetape4k BOM 의존 선언, `ExperimentalR2dbcDaoApi` opt-in, `suspendTransaction()` 안의 `newSuspend`/Flow 예제, 트랜잭션 간 `attach()` 사용, JDBC/R2DBC DAO 조합 표, 호출자 소유 풀·종료 책임, 취소·롤백 관측 신호, 실험 상태와 업그레이드 위험을 설명한다. URL과 인증 정보는 환경 변수 placeholder만 사용하고 원문 URL/SQL/tenant ID를 로그로 남기는 예제는 두지 않는다. 별도 Kotlin wrapper가 없으므로 새 API KDoc은 N/A로 기록한다.
10. 중앙 매뉴얼 사이트의 EN/KR 모듈 문서, manifest, 생성 inventory와 양쪽 랜딩 페이지의 언어별 내용 일치에 출시 전 내용을 반영한다. 2.2.0 출시가 생성되기 전에는 안정 문서의 `releaseRef`를 미출시 ref로 바꾸지 않는다. 안정 랜딩 페이지는 정확한 release ref/commit을 고정하고 `validate_release_manuals.rb` 검증을 통과한 뒤에만 연결한다.
11. README와 중앙 매뉴얼에 지원·미지원 모듈 조합을 명시하고 기존 R2DBC 저장소 helper와 새 R2DBC DAO를 같은 클래스패스에서 사용할 수 없다고 경고한다. 더 넓은 조합 지원은 후속 설계 이슈 [#954](https://github.com/bluetape4k/bluetape4k-exposed/issues/954)에서 다룬다.

## 호환성, 범위와 중단 조건

- 기존 JDBC DAO와 일반 R2DBC repository의 공개 API·동작을 변경하지 않는다.
- Exposed R2DBC DAO는 실험 기능으로 문서화한다. 현재 지원 증거는 catalog가 고정한 1.6.1에 한정하고, 공유 version ref가 바뀌면 소비자 검증과 API 계약 검증을 다시 통과해야 한다.
- Exposed 원본 API가 요구한 계약을 재현할 수 없거나 JDBC DAO 격리를 게시 POM과 소비자 의존성 그래프에서 증명하지 못하면 지원을 보류한다.
- 중앙 catalog alias가 준비되지 않으면 선행 이슈를 완료하기 전까지 Gradle 의존성 구현을 시작하지 않는다.
- 이 설계는 코드 작성, PR 생성, 병합, 게시, catalog tag 발급을 승인하지 않는다. 각각은 후속 계획·승인 절차에 따른다.

## 수용 증거와 DoD

- [ ] 새 모듈과 immutable catalog ref/checksum으로 Exposed 1.6.1 R2DBC DAO를 재현 가능하게 resolve한다.
- [ ] 임시 저장소 게시를 거친 Kotlin 소비자에서 JDBC DAO 격리와 실험 API opt-in 양성·음성 경로를 증명한다.
- [ ] `newSuspend`, 트랜잭션 안의 relation Flow, 동시 트랜잭션 분리, `attach()` 오류·force 경로, 취소·롤백·커넥션 반환 테스트가 통과한다.
- [ ] JDBC API/ABI를 보존하고 의존성 재노출 전용 ABI 허용 목록과 validator 테스트를 완성한다.
- [ ] CI 경로 필터·필수 작업·테스트 산출물을 활성화하고 Kover 적용 가능성 또는 N/A 근거를 기록한다.
- [ ] README EN/KR 및 중앙 매뉴얼 EN/KR/manifest/inventory를 갱신하고 출시 전후 인계 절차를 기록한다.
- [ ] 비밀 정보가 노출되지 않는 예제와 지원·미지원 DAO 모듈 조합 경고를 제공한다.
- [ ] SPW-01..05와 명세 6개 관점 및 통합 검토에서 P0=0/P1=0이고, 미해결 P2는 근거와 추적 링크가 있다.
- [ ] 구현 전에 별도의 사용자 승인 계획을 완료한다.

## 참고 자료

- JetBrains Exposed: [Adding dependencies](https://www.jetbrains.com/help/exposed/adding-dependencies.html#modules), [R2DBC DAO migration](https://www.jetbrains.com/help/exposed/migrating-from-jdbc-to-r2dbc-dao.html), [What's new in 1.6.0](https://www.jetbrains.com/help/exposed/whats-new-160.html#r2dbc-dao-support), [Transactions](https://www.jetbrains.com/help/exposed/transactions.html#suspend-based-transaction).
- Pinned upstream source: [Exposed 1.6.1 `exposed-dao-r2dbc` build](https://github.com/JetBrains/Exposed/blob/73ddea063d539e4f98f01c5130b0bbcfe694f925/exposed-dao-r2dbc/build.gradle.kts), [experimental annotation](https://github.com/JetBrains/Exposed/blob/73ddea063d539e4f98f01c5130b0bbcfe694f925/exposed-dao-r2dbc/src/main/kotlin/org/jetbrains/exposed/v1/dao/r2dbc/ExperimentalR2dbcDaoApi.kt), [EntityClass `newSuspend` and `attach`](https://github.com/JetBrains/Exposed/blob/73ddea063d539e4f98f01c5130b0bbcfe694f925/exposed-dao-r2dbc/src/main/kotlin/org/jetbrains/exposed/v1/dao/r2dbc/EntityClass.kt).
- Local anchors: `settings.gradle.kts:170-174`; `exposed/r2dbc/build.gradle.kts`; `exposed/core/build.gradle.kts`; `gradle/libs.versions.toml`; `build.gradle.kts:338-347, 1103-1125, 1443-1468`; `buildSrc/src/main/kotlin/CheckProductionAbiTask.kt`; `buildSrc/src/main/kotlin/ProductionAbiSupport.kt`; `buildSrc/src/test/kotlin/ProductionAbiSupportTest.kt`; `.github/workflows/ci.yml:177-185, 813-861, 2142-2160`; `scripts/publication/validate_downstream_consumer.rb`; `utils/batch/consumer-fixtures/r2dbc-jackson3-runtime/`.
- Central manual anchors: `bluetape4k.github.io/AGENTS.md` and `docs/manual/bluetape4k-exposed/manifest.yaml`. The local site checkout was read-only and behind its remote by three commits; re-pin `origin/develop` before implementation.
- Prior catalog precedents: dependencies issues [#213](https://github.com/bluetape4k/bluetape4k-dependencies/issues/213) and [#217](https://github.com/bluetape4k/bluetape4k-dependencies/issues/217); neither is an alias for `exposed-dao-r2dbc`. The new prerequisite is [#293](https://github.com/bluetape4k/bluetape4k-dependencies/issues/293).

## 명세 검토 통합 결과

| 관점 | 초기 결과 | 후속 확인 | 통합 처리 |
|---|---|---|---|
| 성능 | P2 1건 | 재검토 PASS | 자체 처리량·지연시간 SLA와 성능 측정은 범위 밖으로 명시하고 Exposed 원본 비용·수집 의미를 고정 |
| 안정성 | P1 2건, P2 3건 | 재검토 PASS | 트랜잭션 안의 Flow 수집, 커넥션 획득 뒤 취소·롤백·풀 재사용, attach 예외·force, H2와 PostgreSQL 동시성 기준을 명시 |
| 보안 | P2 3건 | 실행 호스트 오류로 독립 재검토 불가; 현재 세션에서 문서 확인 | 환경 변수 기반 예제, catalog 출처·checksum, 1.6.1 선택, 대체 출처 부재, opt-in 누락 컴파일을 확인 기준에 추가 |
| 운영 | P1 1건, P2 3건 | 실행 호스트 오류로 독립 재검토 불가; 현재 세션에서 문서와 CI/site 근거 확인 | 중앙 EN/KR 매뉴얼·출시 전후 handoff, 필수 CI 작업·산출물, 임시 Maven 저장소 소비자 검증, 호출자 소유 관측 경계를 명시 |
| 개발자/API | P1 1건, P2 2건 | 실행 호스트 오류로 독립 재검토 불가; 현재 세션에서 ABI·소비자 기준 확인 | 빈 ABI 전용 허용 목록과 검증 테스트, opt-in 누락 경로, 지원·미지원 조합 표를 명시 |
| 사용자/호출자 | P2 2건 | 현재 세션에서 변경 문구 확인 | BOM 의존 선언, 트랜잭션 범위 예제, `attach()` 안내와 JDBC/R2DBC 혼용 금지 경고를 README 기준에 추가 |
| 통합 검토 | 6개 관점 수렴 후 | PASS | 검토 항목 중복을 합쳤다. P0=0, P1=0이며 미해결 P2 하나는 [#954](https://github.com/bluetape4k/bluetape4k-exposed/issues/954)에서 추적한다. |

### 통합 검토 항목 처리

| ID | 심각도 | 발견 사항 | 처리와 근거 |
|---|---|---|---|
| STAB-01 | P1 | relation Flow 수집의 트랜잭션 범위가 불명확함 | § 트랜잭션 및 수용 기준 3에서 활성 `suspendTransaction()` 안의 `toList()`/`count()`로 제한 |
| STAB-02 | P1 | 취소 시 롤백·커넥션 반환 증거가 부족함 | § 트랜잭션 및 수용 기준 5에서 구독·커넥션 획득 뒤 동기화 취소, 이벤트 기록, 롤백, 단일 커넥션 풀 재사용과 자원 정리를 확인 |
| API-01 | P1 | 현재 ABI 목록이 공개 API가 없는 게시 모듈을 허용하지 않음 | 모듈 경계와 수용 기준 7에서 정확한 허용 목록, 존재하는 빈 baseline/dump, validator 테스트, 자체 class 부재를 요구 |
| OPS-01 | P1 | 중앙 사용자 매뉴얼과 release-ref handoff가 빠짐 | 수용 기준 10에서 EN/KR manifest/inventory와 출시 전 문서, 정확한 release ref/commit 및 release validator를 분리 |
| PERF-01 | P2 | 처리량·지연시간 기준이 없음 | § 성능 범위에서 모듈 자체 SLA/성능 측정을 제외하고 Exposed 원본의 비용 계약을 명시 |
| STAB-03 | P2 | `attach()` 예외와 `force = true` 결과가 모호함 | 수용 기준 4에서 예외 타입, 변경 값 폐기, 후속 트랜잭션의 미저장을 확인 |
| STAB-04 / SEC-01 | P2 | 공유 catalog ref 변경과 의존성 출처 추적이 약함 | 수용 기준 1과 호환성 절에서 불변 SHA/checksum, 정확한 1.6.1, 대체 출처 부재와 재검증을 요구한다. 선행 이슈는 [dependencies#293](https://github.com/bluetape4k/bluetape4k-dependencies/issues/293)다 |
| STAB-05 | P2 | DB별 및 동시 트랜잭션 context 범위가 약함 | H2 PR 테스트에서 겹치는 트랜잭션을 검증하고 PostgreSQL 야간 CI에도 연결 |
| SEC-02 / API-02 | P2 | 실험 API opt-in이 소비자에게 계속 필요한지 검증하지 않음 | 수용 기준 6에서 opt-in 컴파일 성공과 opt-in 누락 시 컴파일 진단을 요구 |
| OPS-02 | P2 | 경로 필터가 변경을 감지해도 테스트가 건너뛰어질 수 있음 | 수용 기준 8에서 `test-r2dbc-h2`, `ci-status`, 정확한 Gradle task와 XML 산출물을 고정하고 Kover N/A 이유를 기록 |
| OPS-03 | P2 | 일반 downstream validator가 Kotlin 소비자/POM 격리를 증명하지 못함 | 수용 기준 6에서 임시 Maven 게시 좌표로 Kotlin 컴파일·실행과 해석된 의존성 그래프를 확인 |
| OPS-04 / SEC-03 | P2 | 관측성과 로그 소유 경계가 명시되지 않음 | § 트랜잭션에서 호출자가 관측과 종료를 소유하고, 환경 변수 placeholder를 사용하며 비밀·URL·SQL을 기록하지 않도록 명시 |
| API-03 | P2 | repository helper와 새 DAO의 조합이 지원되지 않음 | 비지원 조합을 문서화하고 후속 모듈 경계 설계 [#954](https://github.com/bluetape4k/bluetape4k-exposed/issues/954)에 연결한다. #942 진행은 막지 않음 |
| USER-01 | P2 | 소비자가 의존성·opt-in·Flow 범위를 재현하기 어려움 | 수용 기준 9에서 BOM 선언, `suspendTransaction()` 예제, `attach()`와 조합표를 요구 |

## Writer gate

- SPW-01 — PASS: #942 범위·별도 모듈 결정·제외 범위·선행 issue #293·후속 issue #954·구현 승인 중단 지점을 명시했다.
- SPW-02 — PASS: 대안, 제약, 실패 모드, 호환성, 11개 수용 기준, DoD와 리뷰 disposition을 연결했다.
- SPW-03 — PASS: 자연스러운 한국어 문장과 용어를 수동 검토하고 contextual terminology audit을 통과했다. API·식별자·경로·명령·URL은 보존했다.
- SPW-04 — PASS: Exposed 1.6.1 공식 문서와 고정 소스, 현재 ABI helper/test, CI job/path/artifact, catalog 및 downstream fixture 경로를 대조했다.
- SPW-05 — PASS: 최종 Markdown 전체를 다시 읽고 표·목록·링크·수용 기준 번호를 확인했다. 이 문서는 설계 검토 증거를 포함하며, workflow receipt의 전체 issue 완료 증거는 구현이 승인되고 끝난 뒤 helper로 기록한다.
