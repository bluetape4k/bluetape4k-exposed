# Issue #925 Guava Dependency Submission 경계

## 배경

중앙 catalog는 `com.google.guava:guava`를 `33.7.2-jre`로 관리하고 있었지만,
Dependency Submission이 생성한 detached configuration에서는 일부 전이 dependency가
`33.4.8-android`, `33.3.1-android`, `29.0-android`, `19.0`을 그대로 선택했습니다.
일반 `runtimeClasspath`의 resolution rule 통과만으로는 라이브 Dependabot 경보가
해결되지 않았습니다.

## 원인

`configureCentralDependencySnapshotResolution()`은 repository project의 등록된
configuration에 resolution rule을 추가합니다. 반면 Dependency Submission의
detached configuration은 이 경로를 우회하므로, 제출 그래프가 읽는 component
metadata의 전이 edge가 그대로 남습니다.

## 결정

중앙 catalog 버전을 `centralSecurityVersions`와
`centralDependencySnapshotVersions`에 모두 등록하고, 경보 그래프에서 확인된
Google, ClickHouse, Cassandra, OpenCensus parent module의 component metadata에서
Guava dependency edge를 제거한 뒤 `33.7.2-jre`로 다시 추가합니다. 이 경계는
project configuration과 detached Dependency Submission graph를 모두 고정합니다.
현재 제출 그래프에서 Guava edge가 확인되지 않은 `com.clickhouse:jdbc-v2`는
variant 범위를 불필요하게 넓히지 않도록 대상에서 제외합니다.

## 검증

- `VerifyGuavaDependencySubmissionMetadataTest`가 7개 exact-head parent edge의
  `requested`와 `selected` 값을 모두 `33.7.2-jre`로 확인합니다.
- `verifyCentralDependencySnapshotAlignment`가 `absent coordinates: none`으로
  통과합니다.
- 라이브 Dependency Submission 재실행 후 Guava alert read-back이 0건인지 확인해야
  합니다.

## 향후 메모

새로운 중앙 보안 dependency를 추가할 때는 project classpath의 선택 결과뿐 아니라
Dependency Submission이 사용하는 detached configuration의 `requested` edge와
라이브 Dependabot alert를 함께 검증합니다.
