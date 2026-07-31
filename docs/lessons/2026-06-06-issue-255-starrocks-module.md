# 이슈 #255 StarRocks 모듈 교훈

## 배경

이슈 #255는 #227의 OLAP 로컬 테스트 가능성 조사에서 StarRocks를 가장 유력한 다음
후보로 선정한 뒤, 로컬에서 검증 가능한 StarRocks Exposed 모듈을 추가했습니다.

## 결정

첫 모듈은 의도적으로 좁게 유지합니다. 네이티브 Connector/J 연결, Exposed dialect
등록, 명시적 데이터베이스 부트스트랩, 메타데이터 탐색, 간단한 StarRocks 테이블 DDL,
insert/select 스모크 테스트, CI/Nightly 노출만 포함합니다. MySQL/PostgreSQL/Trino/
ClickHouse와의 동등성을 주장하지 않습니다.

## 결과

StarRocks 용량 준비 상태 프로브를 추가한 뒤 모듈이 통과했습니다. 올인원 이미지가
백엔드 기동을 마치기 전에는 단순 포트 대기와 `SELECT 1`만으로 부족했습니다. 이때
테이블 생성은 여전히 `Cluster has no available capacity`로 실패할 수 있기 때문입니다.

## 검증 근거

- `./gradlew projects --no-configuration-cache --no-daemon`
- `./gradlew :bluetape4k-exposed-starrocks:dependencyInsight --dependency starrocks-connector-j --configuration runtimeClasspath --no-configuration-cache --no-daemon`
- `./gradlew :bluetape4k-exposed-starrocks:compileKotlin --no-configuration-cache --no-daemon`
- `./gradlew :bluetape4k-exposed-starrocks:cleanTest :bluetape4k-exposed-starrocks:test --no-build-cache --no-configuration-cache --no-daemon`
- `./gradlew :bluetape4k-exposed-starrocks:koverXmlReport --no-configuration-cache --no-daemon`
- `./gradlew :bluetape4k-exposed-starrocks:compileTestKotlin --no-configuration-cache --no-daemon`
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
- `git diff --check`

## 향후 보호 장치

StarRocks 또는 유사한 OLAP 올인원 컨테이너의 준비 상태는 TCP 준비 또는 `SELECT 1`만이
아니라 최소 테이블 생성/삭제 경로로 증명해야 합니다. 백엔드별 DDL 동작을 컨테이너
테스트로 검증할 때까지 첫 dialect 표면을 작게 유지하세요.
