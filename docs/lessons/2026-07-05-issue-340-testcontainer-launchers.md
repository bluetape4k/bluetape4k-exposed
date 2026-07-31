# 이슈 340 Testcontainer 런처 정리

## 변경 사항

BigQuery와 StarRocks 테스트 컨테이너 설정을 런처 형태의 픽스처 내부로 옮겼다.

- `BigQueryEmulator.Launcher.endpoint`는 로컬 엔드포인트와 컨테이너 엔드포인트 탐색을 한곳에서 처리한다.
- `StarRocksTestServer.Launcher.starRocks`는 컨테이너 시작, 매핑된 포트, 자격 증명, JDBC URL, 종료 등록을 한곳에서 처리한다.

## 반복 적용할 사항

- 원시 `GenericContainer` 생성은 역할이 한정된 런처 헬퍼 내부에 둔다.
- 엔드포인트, 매핑된 포트, 자격 증명, JDBC URL은 타입이 지정된 픽스처 속성으로 노출한다.
- 컨테이너 종료 작업을 `ShutdownQueue`에 등록한다.
- 데이터베이스 준비 상태를 확인하기 전에 호스트와 포트 매핑을 검증한다.
- Testcontainers를 사용하는 BigQuery와 StarRocks 테스트는 순차적으로 실행한다.

## 검증 결과

- BigQuery와 StarRocks 테스트 컴파일이 통과했다.
- 순차 실행한 대상 테스트에서 BigQuery 46개와 StarRocks 21개가 통과했다.
