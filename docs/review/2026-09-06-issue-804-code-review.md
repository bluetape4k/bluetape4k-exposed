# #804 해시 계약 구현 리뷰

## 범위와 판정

기준: `origin/develop=9f86e603`, 승인된 #804 설계와 이 브랜치의 JDBC/R2DBC 테스트,
두 build 파일, README 6개, CHANGELOG 및 설계·계획·교훈 문서.
production Kotlin 변경은 없다. 최종 판정은 **PASS, P0=0 / P1=0**이다.

독립 리뷰 `review804_final`은 판정을 반환하지 않아 제한 시간 후 중단했다.
아래 결과는 main의 **inline fallback review**이며 독립·외부 모델 검증이 아니다.
설계 리뷰의 별도 timeout/thread-limit 기록도 유지한다.

## 모듈별 관점 검토

| 관점 | JDBC | R2DBC | 근거 |
|---|---|---|---|
| 성능 | PASS | PASS | production 경로 변화 없음. BCrypt 4/5는 테스트 전용이라고 README에 명시 |
| 안정성 | PASS | PASS | backend별 withTables 사용, nullable·재저장·업그레이드 대상 8건씩 통과 |
| 보안 | PASS | PASS | raw 저장값·wrong input·표시값·SQLSTATE 23505 실패 검증, 임의 로거까지 안전하다는 주장 없음 |
| 운영 | PASS | PASS | spring-core/BC runtime 전제 및 로그 제한 명시, CPU 비용·DB 경합 정책은 호출자 책임 |
| 개발자/API | PASS | PASS | 새 production API·ABI 없음, ABI 검사 통과, 두 POM에 신규 테스트 의존성 없음 |
| 사용자 | PASS | PASS | README 양언어의 직접 사용·nullable·double hash 방지·Tink 링크 일치 |

각 backend의 최종 P0/P1/P2/P3 미해결 건수는 0/0/0/0이다.
암호화 기능의 안전성 전체를 보증하는 리뷰가 아니라 승인된 테스트·문서 diff의 판정이다.

## 지적과 처리

- P2, 두 계약 테스트의 SQL 오류 검증: 예외 타입만 넓게 검사하면 다른 실패가 의도한
  DB 실패로 오인될 수 있다. `SQL logger와 중복 키 예외에 평문이 포함되지 않는다`에서
  JDBC SQLException / R2DBC R2dbcException의 SQLSTATE `23505` 및 INSERT 기록 검사로 보강했다.
- P2, 두 README 의존성 예제: 실제 Spring Security 7.1.1은 spring-core 없이 matches가 실패했다.
  testRuntimeOnly와 README runtimeOnly, BOM 전제를 추가했다. production POM에는 넣지 않았다.
- P3, README 코드 예제: `Table` import 누락을 보완했다.
- 독립 리뷰 실행 실패는 P0/P1 판정이 아니며, 유효한 반대 의견을 폐기하지 않았다.

## 수용 기준 추적

명세 1·3: 네 알고리즘 parameterized 테스트. 명세 2: raw hash 동일성과 custom hash 호출 수.
명세 4: BCrypt 비용·PBKDF2 전환 두 경우. 명세 5: transaction-local logger·DB 오류·negative control.
명세 6: 두 모듈 ABI·POM 및 production 변경 없음. 별도 adapter·BOM alias·새 KDoc는 새 API가 없어 N/A다.

## 검증과 한계

- 신규 테스트 16/16 통과, 제외 0.
- H2 전체 JDBC 111건(106 통과·기존 제외 5), R2DBC 102건(97 통과·기존 제외 5), 실패 0.
- 두 모듈 detekt·checkKotlinAbi·POM 생성 통과, POM은 `--no-configuration-cache`로 실행.
- 새 PostgreSQL/MySQL 해시 조합, R2DBC DAO, production 해시 비용, 전체 애플리케이션 로깅은 미검증.
- CI와 실제 PR exact-head read-back은 PR 생성 후 별도 확인한다. 머지는 이번 승인에 포함되지 않는다.

## 문서 검증

명세 보완·계획 보완·본 리뷰·교훈 각각 SPW-01~05를 검토했다.
한국어 개발자 문서의 목적·근거·처리·한계를 분리했고, 테스트 수·API·코드 예제를 현재 파일과 대조했다.
KO-01~06 의미·용어·문장·링크 검토 완료. KO-07 용어 감사와 diff check 결과는 PR 검증에 기록한다.
