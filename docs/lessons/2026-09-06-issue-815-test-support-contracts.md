# #815 테스트 fixture의 취소·호환성 검증에서 확인한 경계

## 상황과 결정

custom DB selector를 쓰는 소비자가 JDBC/R2DBC 테스트 lifecycle을 복사하지 않도록 기존 두 artifact에 fixture adapter를 추가했다. 기존 enum API를 변경하거나 공용 상위 module을 만들지 않고, fixture 인스턴스가 직렬화와 기본 wrapper를 소유하도록 했다. 외부 pool과 domain seed는 호출자 책임이다.

제공자 구현은 `4125687f`, 추가 취소·미지원 schema 검증은 `99fea3e9`다. 승인 명세와 계획은 `docs/superpowers/`의 같은 날짜 issue815 문서다.

## 실패에서 얻은 교훈

1. **permit 소유 표시를 dispatcher 반환보다 먼저 설정한다.** JDBC acquire는 interruptible IO 경계에서 수행하고 같은 바깥 finally가 반환한다. 취소 전에 획득했는지 기록하지 않으면 본문에 도달하지 않은 호출도 permit을 누수할 수 있다.
2. **IO 경계와 호출자의 본문 dispatcher는 다르다.** 초기 구현의 명시적 dispatcher 회귀 테스트가 실패했다. 획득·연결 생성은 IO에서 수행하되 본문은 요청한 dispatcher로 돌려보냈다.
3. **최초 configure 실패도 baseline 관찰 계약을 가진다.** 기본 wrapper 초기화 뒤 임시 구성 생성이 실패하면 내부 transaction try에 진입하지 않는다. 양쪽 legacy db 복원은 더 바깥 finally에도 필요했다.
4. **예외 identity와 coroutine debug 복제를 구분한다.** 상태 없는 예외는 stacktrace recovery 과정에서 복제될 수 있다. provider가 원래 실패를 바꾸는지 검증할 때 추가 상태를 가진 테스트 예외로 복제 효과를 분리했다.
5. **부분 DDL 실패의 opt-out은 drop 호출 여부로 검증한다.** PostgreSQL의 rollback 때문에 테이블이 남지 않는 결과만으로 provider가 opt-out을 무시했다고 판단할 수 없다. 종료 drop 미호출과 정상 cleanup 후 metadata를 각각 확인했다.
6. **ABI dump만으로 소비자 호환성을 주장하지 않는다.** 이전 JAR로 한 번 컴파일한 class를 신규 JAR와 실행하고 class·실제 resolved JAR의 checksum을 확인했다. 같은 version 좌표의 캐시 혼용도 탐지한다.
7. **검증 task 순서도 계약이다.** updateKotlinAbi와 checkKotlinAbi를 같은 Gradle invocation에 넣자 task dependency 검증이 실패했다. 계획대로 두 invocation으로 분리했다.
8. **enum 내부 callback은 getter mock으로 계측되지 않을 수 있다.** JDBC connect는 같은 클래스의 필드를 직접 읽는다. 실제 callback을 잠시 교체하고 finally에서 복원해 호출 횟수를 검증했다. 이 계측 실패를 제품 코드의 RED/GREEN 증거로 세지 않았다.
9. **재개 시 테스트 환경과 로그 보존을 다시 확인한다.** 중단 전 성공을 확인했더라도 재개 후 Colima가 중지되고 임시 로그가 사라질 수 있다. 실제 Docker 연결을 확인하고 실패 로그를 보존한 채 순차 재검증했다. 제출에 필요한 최종 로그는 작업 트리의 build 경로에 남긴다.

로그 테스트는 처음에 클래스 logger를 수집했으나 JDBC가 package logger를 사용해 빈 결과로 실패했다. 실제 logger 이름을 확인하고 provider package만 수집했다. 빈 로그를 민감값 미노출의 성공 증거로 처리하지 않는다.

## 결과와 남은 범위

JDBC 전체 217건 중 201건 통과·기존 조건부 제외 16건, R2DBC 전체 198건 중 184건 통과·기존 조건부 제외 14건이다. 실패·오류는 0건이다. 최종 PostgreSQL cleanup 각 8건, Detekt, ABI, 이전 소비자 11개 진입점 linkage, POM·metadata 검증도 통과했다. 상세 명령과 로그는 [구현 리뷰](../review/2026-09-06-issue-815-implementation-review.md)에 있다.

unregister 자체 실패 주입과 legacy beforeConnection 직접 계측을 양쪽 각 4건 보강했다. 최초 실패의 identity·suppressed 순서·permit·재초기화를 확인했다. #817의 upstream cleanup 로그 한계는 그대로다.

독립 코드·아키텍처 에이전트는 thread limit으로 실행하지 못했다. 코드 리뷰는 기존 승인에 따라 inline으로 수행했고, 아키텍처 검토는 별도 사용자 승인 후 inline으로 수행했다. 독립 검토 provenance를 주장하지 않는다. PR·CI·머지·배포·downstream 이전은 별도 게이트다.

## 다음 변경의 검증 기준

- 같은 DB에는 같은 fixture를 공유한다. key를 전역 registry 식별자로 바꾸지 않는다.
- 취소 테스트는 대기·획득 후·본문의 위치를 구분하고 후속 호출과 permit 수를 확인한다.
- 초기화 실패와 transaction 실패를 같은 try 영역으로 가정하지 않는다.
- cleanup은 요청한 table/schema와 provider 등록만 대상으로 한다. 공유 운영 schema를 입력하지 않는다.
- 테스트 실패를 retry PASS로 지우지 말고 dispatcher·debug 복제·DB rollback·실제 logger를 먼저 확인한다.
- 독립 검토 실행 실패는 코드 결함 판정과 다르다. inline provenance, 필수 검토 미완료, PR 경계를 각각 기록한다.

## 문서 검증 DoD

- [x] SPW-01: 한국어 개발자용 lesson, 구현 SHA·실패 로그·리뷰 근거 연결.
- [x] SPW-02: 상황·결정·실패·결과·한계·다음 검증 기준 포함.
- [x] SPW-03: KO-01–KO-06 검토, 숫자·예외 의미·책임 경계 보존.
- [x] SPW-04: 구현 리뷰와 결과·미실행 범위 대조.
- [x] SPW-05: 최종 read-back과 용어 audit 완료, 숫자와 미완료 경계를 구현 리뷰와 대조.
