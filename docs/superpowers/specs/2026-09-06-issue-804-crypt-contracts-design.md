# #804 upstream 해시 컬럼 직접 사용 계약

## 승인과 범위

사용자는 upstream 직접 사용, 공통/Tink 모듈에 Spring Security를 강제하지 않는 설계와 JDBC/R2DBC 검증·PR 생성을 승인했다. 기준은 Exposed 1.5.0과 develop `9f86e603`이다. 신규 production 모듈, 암호 구현, catalog 변경, 배포와 머지는 제외한다.

## 근거와 선택

Exposed 1.5.0의 `Hasher`, `Hashed`, `hashed()`와 column-bound `hash()`가 nullable·custom hasher·읽은 값 재저장을 이미 지원한다. `Hashed.toString()`은 `Hashed(***)`다. 공개 `encodedValue`와 custom hasher는 자동으로 안전해지지 않는다.

- 채택: 기존 JDBC/R2DBC test-support의 테스트 소스에서 upstream을 직접 사용하고 README 예제를 제공한다.
- 기각: 단순 재수출 adapter. 새 API 없이 의존성·ABI 유지 부담만 늘어난다.
- 기각: 암호 알고리즘·rehash 서비스 구현. 정책과 비밀값 수명은 애플리케이션 책임이다.

공식 기준: [태그 소스](https://github.com/JetBrains/Exposed/tree/84361204b6639cad5696506a26595c97afac3531/exposed-crypt). upstream POM은 `spring-security-crypto:7.0.0`을 compile 의존성으로 갖는다. 따라서 Spring 프레임워크 비의존과 Spring Security Crypto 비의존을 혼동하지 않는다. JDBC test-support의 기존 crypt 의존성은 유지하고 R2DBC에는 testImplementation만 추가한다. BouncyCastle도 선택 알고리즘 검증의 testRuntimeOnly로 한정한다.

## 검증 계약

1. JDBC와 R2DBC H2에서 BCrypt 저장·조회·올바른/틀린 입력 matches, raw 컬럼에 평문 미저장, nullable null 보존을 검증한다.
2. 조회한 Hashed를 다시 저장한 raw hash가 동일하고, custom Hasher 호출 횟수가 재저장 시 증가하지 않아야 한다.
3. Argon2, PBKDF2, SCrypt도 저장·조회·matches 경로를 검증한다. 테스트용 저비용 파라미터를 production 권장값으로 문서화하지 않는다.
4. BCrypt strength 변경과 DelegatingPasswordEncoder 알고리즘 전환은 기존 hash 검증 → upgradeEncoding → 인증 성공 후 새 hash 저장 순서다. 평문 없이 일괄 rehash하거나 hasher 교체만으로 이전 hash를 변환하지 않는다.
5. Hashed 표시값과 SQL logger에 평문이 없어야 한다. 중복 키 실패의 exception chain에도 평문이 없어야 한다. custom hasher가 평문을 반환하거나 예외에 넣는 경우까지 upstream이 방어한다고 주장하지 않는다.
6. CI는 기존 JDBC/R2DBC test-support 검사 경로를 사용한다. API/ABI와 publication POM에 production 의존성 변화가 없어야 한다.

## 실패 모드와 책임

- 이미 해시된 값을 다시 hash하면 이중 해시가 된다. 저장된 Hashed는 그대로 대입한다.
- BCrypt 60자만 기준으로 컬럼을 좁히면 알고리즘 전환이 실패한다. 예제는 varchar(512), 실제 길이와 DB 제한은 호출자가 확인한다.
- BouncyCastle 미설치 시 Argon2/SCrypt 생성이 실패한다. 명시적 runtime 의존성과 실패 계약을 설명한다.
- 커스텀 encoder·요청 로거·encodedValue 직접 출력은 별도 유출 경로다. 요청·평문·해시 로깅 금지, 일반화한 인증 실패 메시지와 최소 관측 정보 정책을 문서화한다.
- 해시는 CPU/메모리 집약 작업이다. production 비용은 caller가 측정하고 이벤트 루프에서 실행하지 않는다. 이 작업은 성능 권장값을 정하지 않는다.

## 수용 기준과 완료 상태

이슈 수용 기준은 위 1–6 및 양언어 README, Tink와의 책임 분리 설명으로 연결한다. schema·transaction cleanup은 기존 withTables fixture가 소유한다. 실패 시 production API가 아닌 신규 테스트·문서·test dependency만 되돌릴 수 있다.

- 설계 방향: 사용자 승인 완료.
- 설계 리뷰·실행 검증·PR: 대기.
- 외부 게시·배포·머지: 별도 승인 없으므로 실행하지 않는다.
