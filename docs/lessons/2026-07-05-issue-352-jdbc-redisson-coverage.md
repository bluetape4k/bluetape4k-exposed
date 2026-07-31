# 교훈과 학습: Issue #352 jdbc-redisson 커버리지

## 배경

`jdbc-redisson`의 명령어 커버리지는 현재 저장소의 모듈 평균에 조금 못 미쳤다.
이 이슈에는 소폭의 개선만 필요했으므로, Testcontainers 의존도가 높은 시나리오를
추가하기보다 결정적인 분기 커버리지를 제공하는 프로덕션 계약을 대상으로 삼는
것이 위험이 가장 낮았다.

## 효과적이었던 접근

- Kover XML을 파싱한 결과, `JdbcRedissonRepository.kt`는 명령어 커버리지가
  낮으면서 단순한 기본 메서드 분기를 노출하고 있었다.
- `MockK` `RMap` 검증으로 Redis를 시작하지 않고도 Redisson 위임, 유효성 검사,
  빈 입력 조기 반환, 패턴 무효화를 커버했다.
- 테스트를 인터페이스 계약 수준으로 유지하여 프로덕션 코드 변경을 피하고 diff를
  새 테스트 파일 하나로 제한했다.
- 최초 실행에서 Redisson closed-channel 실패가 발생한 뒤 전체 모듈을 다시
  실행하여, 해당 실패가 일시적이며 새 테스트 때문에 발생한 것이 아님을 확인했다.

## 근거

- 기준 XML 명령어 커버리지: `80.49%`.
- 최종 XML 명령어 커버리지: `82.61%`.
- 최종 모듈 테스트/Kover 명령:
  - `./gradlew --no-parallel :bluetape4k-exposed-jdbc-redisson:test :bluetape4k-exposed-jdbc-redisson:koverXmlReport :bluetape4k-exposed-jdbc-redisson:koverLog`
  - 결과: `446 passing`, `BUILD SUCCESSFUL`.

## 향후 주의 사항

Redis 기반 모듈의 작은 커버리지 격차를 해소할 때는 새로운 컨테이너 기반
시나리오를 추가하기 전에, 커버되지 않은 인터페이스 기본 메서드를 모의 객체 기반
계약 테스트로 검증할 수 있는지 확인한다.
