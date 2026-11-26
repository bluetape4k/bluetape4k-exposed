# #942 Exposed R2DBC DAO 연동 작업 교훈

## 맥락

JetBrains Exposed 1.6.1의 실험 R2DBC DAO를 Bluetape4k 소비자가 사용할 수 있도록 별도 경로를 제공했다. 선행 catalog alias는 dependencies PR #298에서 이미 merge됐고, 기존 `exposed = 1.6.1` version ref를 재사용했다. 따라서 이 저장소의 catalog ref만 해당 merge commit으로 고정하고 별도 version alias나 tag는 만들지 않았다.

## 결정과 결과

- `bluetape4k-exposed-r2dbc-dao`는 upstream DAO artifact와 Exposed core를 노출하는 dependency-only 모듈이다. wrapper API, driver, pool, transaction manager 또는 resource lifecycle을 새로 소유하지 않는다.
- 기존 `bluetape4k-exposed-r2dbc` 경로에는 JDBC DAO가 포함되어 있어 R2DBC DAO와 같은 consumer classpath에서 함께 쓸 수 없다. 이 제한은 #942 문서에 적고 더 넓은 조합 설계는 #954로 남겼다.
- 새 production class가 없는 모듈은 ABI 집합에 계속 포함하되, 빈 baseline과 빈 actual dump를 이 모듈 하나에만 명시적으로 허용했다. 빈 산출물의 전역 허용은 금지한다.
- 중앙 매뉴얼은 EN/KO 모두 `2.2.0` 개발 미리보기로 추가하고 안정 `2.0.0` 문서 기준 경로는 바꾸지 않았다.

## 검증 결과

- H2 계약 실행은 9개 통과했고 PostgreSQL 조건 테스트는 제외됐다. Docker가 제공되지 않는 격리 실행은 Testcontainers 환경을 찾지 못했지만, 호스트의 정상 Colima Docker context를 확인한 뒤 PostgreSQL로 재실행해 계약 테스트 10개 모두 통과했다. 격리 실행 실패는 환경 경계로 분류했고 제품 실패로 집계하지 않았다.
- ABI 검사 46/46, 빈 baseline/actual 각 1개, dependency-only allowlist 1개를 확인했다.
- staged consumer는 47개 publication, 46개 library, fixture 1개, compile/runtime task 각 93개를 통과했다. opt-in 소비자는 컴파일·실행되고 opt-in 누락은 기대한 experimental API 진단으로 실패했다. 새 JAR에는 class가 없다.
- 중앙 사이트의 300개 테스트, manual 문서 검사와 3,921 페이지 빌드는 통과했다. 전체 매뉴얼 검증에 남은 5개 오류는 신규 페이지가 아니라 기존 tenant-jdbc/Tink 불일치이며 사이트 #448에서 추적한다.
- 독립 reviewer 실행 host가 존재하지 않아 독립 code review provenance는 확보하지 못했다. 여섯 관점 검토와 통합은 main session의 inline fallback으로 남겼고, 이를 독립 승인이나 hosted CI 결과로 바꾸어 말하지 않았다.

## 발견한 실수와 보완

- 첫 컨테이너 테스트 시도는 context-mode가 host Docker socket 환경을 전달하지 않아 실패했다. 다음부터는 Testcontainers 검증 전 `colima status`, `docker context show`, `docker info`를 host에서 확인하고, 격리 실행 결과와 실제 host 재실행 결과를 별도로 기록한다. 정상 Colima를 재시작해 해결하지 않는다.
- ABI 검사기는 모든 게시 라이브러리에 비어 있지 않은 API dump가 있어야 한다고 가정했다. dependency-only 모듈을 위해 예외를 두되 `expectedProjects`, baseline, actual dump의 완전성과 allowlist 밖 빈 파일을 fail-closed로 계속 검사한다.
- workflow receipt의 기존 취소된 reviewer lane은 이 실행에서 되살릴 수 없었고 초기 recovery 경로는 session binding과 진단 형식이 맞지 않았다. 현재 세션에 결합된 새 run을 만들고 main lane으로 계속했지만, reviewer 호스트 자체를 복구했다고 주장하지 않는다.

## 다음 작업을 위한 guard

- 외부 catalog 선행 PR이 merge됐는지 live ref에서 먼저 확인하고, 기존 version ref를 재사용할 수 있으면 새 버전을 만들지 않는다.
- dependency-only publication은 ABI, POM/Gradle metadata, JAR class count와 positive/negative consumer 양쪽을 검증한다.
- R2DBC integration 테스트는 H2와 컨테이너 DB를 각각 실행하고, 격리 환경이 Docker를 보지 못하면 host 재실행 결과를 확인할 때까지 전체 DB 검증을 완료 처리하지 않는다.
- 실험 upstream API, JDBC/R2DBC classpath 충돌, caller-owned lifecycle을 README와 양언어 중앙 매뉴얼에 반복해서 명시한다.
- 독립 리뷰 호스트가 없으면 현재 세션의 관점별 검토는 수행할 수 있지만 provenance는 inline으로 기록하고 독립 reviewer gate는 PENDING으로 둔다.
- 중앙 manual 전체 검증의 기존 오류는 새 페이지 결과와 분리해 기존 이슈에 연결하고, 새 페이지 자체의 링크·locale parity·releaseRef는 별도로 확인한다.

## Lesson DoD

- SPW-01: 작업 맥락, upstream/catalog 근거와 사용자 경계를 기록했다.
- SPW-02: 결정, 결과, 검증, miss와 미래 guard를 포함했다.
- SPW-03: 한국어 기술 문체와 일관된 용어를 검토했다.
- SPW-04: merge된 PR, 변경된 소스/문서, 테스트와 issue 상태에 대조했다.
- SPW-05: 최종 Markdown을 다시 읽고 section 흐름과 code token을 확인했다.
