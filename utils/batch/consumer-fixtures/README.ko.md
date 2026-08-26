# Batch 소비자 fixture

이 디렉터리는 게시된 batch 좌표를 선택적으로 사용하는 최소 소비자 계약을
고정합니다. 각 Gradle fixture와 Maven fixture는
`bluetape4k-dependencies`·`bluetape4k-exposed-bom`을 import한 뒤 개별 모듈
버전 없이 batch 좌표를 선언합니다. Gradle fixture는
`ISSUE731_CONSUMER_REPO`에 지정한 임시 Maven 저장소만 먼저 조회하고,
`mavenCentral()`을 보조 저장소로 사용합니다. 전역 `mavenLocal()`은 사용하지
않습니다.

격리된 전체 검증은 저장소 루트에서 다음처럼 수행합니다.

```bash
scripts/batch/validate_consumer_fixtures.sh
```

검증기는 현재 checkout을 임시 저장소에 게시하고 정확한 `sourceHead`를
기록한 뒤 다섯 Gradle fixture를 온라인 사전해결·오프라인 재실행합니다.
`legacy-binary-runtime`은 1.12.1 aggregator를 기준으로 Java 소비자 class를
먼저 컴파일한 후 현재 aggregator로 같은 class를 실행해 deprecated JVM
descriptor bridge를 실제로 확인합니다. `core-custom-json`은 Jackson,
Exposed, JDBC, R2DBC class가 runtime classpath에 없는지도 확인합니다. Maven
fixture는 같은 BOM 좌표로 compile과 JDBC public-type runtime probe를
수행합니다. provenance가 없거나 게시 checkout과 다르면 실패하며, fixture는
비밀·전체 환경 덤프를 남기지 않습니다. `ISSUE731_MAVEN_LOCAL_REPO`를
지정하면 특정 임시 저장소를 보존해 조사할 수 있습니다.
