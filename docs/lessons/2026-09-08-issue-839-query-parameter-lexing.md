# 선언형 SQL 위치 파라미터의 코드 영역 구분

## 원인

전체 SQL 문자열에 정규식을 적용하여 리터럴과 주석의 `?N`까지 바인딩 대상으로 취급했다. 실제 인자가 하나인 SQL에서 리터럴 `'?2'` 때문에 범위 초과 예외가 재현되었다.

## 결정

Spring-neutral common 모듈에 단일 어휘 스캔을 공유한다. 문자열, 인용 식별자, 주석, PostgreSQL dollar quote를 보존하고 실제 위치 파라미터만 출현 순서대로 콜백에 전달한다. JDBC/R2DBC의 타입 변환과 인자 개수 검증은 기존 어댑터에 남긴다. 추가 의존성은 없다.

## 검증

JDBC 실행 경로에서 기존 구현의 범위 초과 RED를 확인했다. 공통 스캐너 5개,
JDBC 37개, R2DBC 74개가 실패·오류·skip 없이 통과했다. 양쪽 adapter의 동일
보호 영역 corpus, 반복·NULL·범위 초과 인자 및 실제 선언형 쿼리를 검증했다.
common/JDBC/R2DBC의 detekt와 ABI 검증도 통과했다.

Exposed 1.5.0의 별도 `StatementContext.expandArgs`는 SQL 주석을 인식하지 않아
바인딩·실행 후 `Slf4jSqlDebugLogger`에서 주석의 `?`를 소비하다 실패했다.
실제 쿼리 통합 테스트에만 해당 logger를 일시 제거하고 finally에서 복구하여
바인더 검증과 upstream 로깅 제약을 분리했다. production logger 설정은 바꾸지
않았으며 사용자 README 양 언어에 알려진 제약을 명시했다.

한 검증 실행에서 Gradle daemon이 비정상 종료했으므로 성공 증거로 사용하지 않았다.
전용 `--no-daemon` 단일 실행으로 위 회귀와 정적 검증을 다시 통과했다.

## 향후 지침

파라미터 치환에 전체 SQL 정규식을 다시 도입하지 않는다. 새 SQL 방언의 인용 규칙은 공통 테스트로 먼저 고정하고 양쪽 어댑터에 같은 계약을 적용한다.
