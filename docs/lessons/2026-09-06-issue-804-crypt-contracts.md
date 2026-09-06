# #804 해시 컬럼 계약의 실행 의존성을 검증한다

## 결정과 경계

Exposed 1.5.0의 `Hasher`·`Hashed`·column-bound `hash`를 직접 사용한다.
재수출 adapter와 암호 구현은 추가하지 않는다. Tink의 가역 암호화, 애플리케이션의
인증·rehash 정책, 테스트 지원 모듈의 계약 검증을 분리한다.

## 드러난 가정과 예방 규칙

1. **POM만으로 실행 의존성을 단정하지 않는다.** upstream crypt는 Spring Security Crypto를
   전이 의존하지만, 이 저장소에서 선택된 7.1.1의 `matches`·`upgradeEncoding`은
   `StringUtils`를 사용한다. 최초 JDBC 실행은 8건 중 7건이
   `NoClassDefFoundError: org/springframework/util/StringUtils`로 실패했다.
   `spring-core`를 testRuntimeOnly로 추가한 뒤 8건이 통과했다.
   다음 선택적 라이브러리 검증에서는 encode뿐 아니라 matches와 migration 경로까지 실행한다.
2. **부정 테스트는 의도한 실패 종류도 확인한다.** 넓은 예외 타입과 문자열 미노출만으로는
   원하는 DB 실패를 증명하지 못한다. JDBC/R2DBC 예외 chain에서 SQLSTATE `23505`와
   INSERT 기록을 확인하도록 보완했다. 평문 검출기의 실패 대조도 유지한다.
3. **해시 객체의 표시값과 비밀값 보호를 구분한다.** `Hashed(***)`는 toString 계약이다.
   공개 encodedValue와 custom hasher·애플리케이션 로그는 보호하지 않는다.
   README에는 평문뿐 아니라 해시·요청 본문 로깅도 금지하도록 책임 범위를 명시한다.
4. **검증 태스크 이름을 먼저 확인한다.** 일반적인 publication 이름을 추정한 명령은 실행 전에
   실패했다. 실제 `BluetapeExposed` publication을 확인하고,
   configuration cache와 POM withXml 충돌은 `--no-configuration-cache`로 우회했다.
   이 우회는 configuration cache 지원을 증명하지 않는다.

## 결과와 증거

- 신규 H2 계약: JDBC 8건, R2DBC 8건, 실패·제외 0건.
- H2 전체: JDBC 111건 중 106건 통과·기존 제외 5건, R2DBC 102건 중 97건 통과·기존 제외 5건.
- 두 모듈 detekt·checkKotlinAbi·POM 생성 통과. 추가한 spring-core·BouncyCastle은 production POM에 없다.
- 재저장 시 raw hash 유지, 네 알고리즘 matches, nullable, custom hasher 호출 수,
  비용·알고리즘 업그레이드, DB 오류·로거의 평문 미노출을 각각 검증했다.
- 증거 로그: `build/issue804/crypt-targeted.log`, `full-validation-no-config-cache.log`.

## 한계

이번 해시 계약은 H2 JDBC/R2DBC DSL 검증이다. 새 PostgreSQL/MySQL 해시 조합, R2DBC DAO,
애플리케이션 전체 로그, production 해시 비용, 실제 publication은 검증하지 않았다.
계약 테스트만으로 일반적인 보안 보증이나 배포 완료를 주장하지 않는다.

## 원본

- [Exposed 1.5.0 crypt](https://github.com/JetBrains/Exposed/tree/84361204b6639cad5696506a26595c97afac3531/exposed-crypt)
- [승인된 설계](../superpowers/specs/2026-09-06-issue-804-crypt-contracts-design.md)
- [실행 계획](../superpowers/plans/2026-09-06-issue-804-crypt-contracts-plan.md)
