# 교훈 — 이슈 #256 Druid JDBC 쿼리 전용 실험 (2026-07-05)

**관련 이슈**: #256
**영향받는 모듈**: `exposed/druid`, 루트 README 언어 파일 세트, CI/Nightly 워크플로

## L1: Druid를 Exposed 방언 호환 대상이 아닌 Avatica 쿼리 인프라로 취급한다

### 문제

Druid JDBC는 Avatica를 통해 SQL 쿼리와 메타데이터 경로를 제공하지만, 공식
빠른 시작 및 JDBC 문서만으로는 Exposed DDL, DML, DAO, 저장소 또는 마이그레이션
추상화의 안전한 기본 대상으로 삼을 수 없다.

### 교훈

쿼리와 메타데이터 도우미만으로 시작한다. 도우미 표면에서 비쿼리 문을 거부하고,
지원하지 않는 영역을 두 README 언어 파일에 모두 문서화한다. 전체 방언 또는 저장소
확장은 실제 Druid 동작을 입증한 후 별도 설계를 거쳐 진행한다.

## L2: Druid 픽스처 스모크 테스트는 명시적이고 직렬이어야 한다

### 문제

Druid Docker/로컬 빠른 시작은 메모리 사용량이 많고 `wikipedia`와 같이 로드된 픽스처
데이터 소스가 필요하다. 이 워크스테이션에서는 `localhost:8888`의 Druid에 접근할 수
없었으므로 라이브 픽스처 스모크 테스트를 수행했다고 주장하면 사실과 다르다.

### 교훈

기본 CI는 컴파일/단위/모듈 테스트로 유지하고, 준비된 로컬/컨테이너 Druid를 대상으로
환경 변수로 활성화하는 스모크 테스트를 제공한다.
`EXPOSED_DRUID_SMOKE=true ./gradlew --no-parallel :bluetape4k-exposed-druid:test --tests '*DruidJdbcSmokeTest'`.
로컬 Druid에 접근할 수 없다면 숨기지 말고 증거 공백으로 기록한다.
