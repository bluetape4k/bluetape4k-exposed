# PR #878 검토와 batch 빌드 복구

## 배경

PR #878의 import·assertion 정리를 현재 `develop`과 통합하고, 기존 `utils/batch` 빌드 실패를 재현했다.
비교 기준은 `0f6d497752ff3cf6e5732d6ec849c8660991a540`이다. 최초 merge-base 기반 diff에는 이미 병합된
PR #872의 변경도 포함되어 있어, 충돌 해결 후 현재 `develop` 대비 순수 변경으로 검토 범위를 다시 확정했다.

## 확인과 결정

- batch core의 JAR 검증 task가 최상위 Gradle script property를 캡처하여 configuration cache에서 실패했다.
  schema 경로 목록을 `tasks.named<Jar>("jar")` 안에서 만들도록 옮기고, SQL 9개를 확인하는 기존 검증을 유지했다.
- XML formatter가 Detekt baseline의 `<ID>` 내부에 개행을 넣어 기존 항목을 인식하지 못했다.
  기존 ID를 복원하고, 실제 긴 Kotlin 구문은 줄바꿈하여 새로운 suppression 없이 해결했다.
- Markdown 표 안의 inline code도 literal pipe를 `\|`로 escape해야 한다.
  Spring JDBC/R2DBC의 영문·국문 README를 수정하고, parity test는 정렬 공백만 정규화하면서 escaped pipe를 확인한다.
- 사용하지 않는 공개 `KLogging` Companion 추가·상속은 ABI 차이를 만들었다.
  세 공개 클래스의 불필요한 변경을 제거하여 기존 ABI를 유지했다.
- `shouldBe`와 `shouldNotBe`는 객체 identity, `shouldBeEqualTo`는 값 동등성을 검증한다.
  nullable receiver, numeric type, 포함 여부, 경계값, 타입 검사, 예외 발생 구간을 함께 검토했다.
  final class의 정확한 클래스 비교를 instance matcher로 바꾼 경우에도 현재 계약은 동일하다.
- 별도 review/architect agent는 사용량 제한으로 최종 검토를 마치지 못했다.
  리더가 현재 기준의 production/test diff를 직접 검토했다. 독립 agent 승인으로 기록하지 않는다.

## 결과와 검증

| 검증 | 결과 |
|---|---|
| 기존 batch 빌드 재현 | Gradle script property 접근 및 configuration cache 오류 확인 |
| batch core/JDBC/R2DBC/aggregator build | 성공, 테스트 545건 중 530건 통과·기존 15건 건너뜀 |
| configuration cache 엄격 검사와 재사용 | 두 번 성공, 두 번째 cache 재사용, schema SQL 9개 확인 |
| 영향 Ktor/Spring Boot 10개 모듈 테스트 | 910건 중 900건 통과·기존 10건 건너뜀, 실패·오류 0건 |
| 전체 production ABI | `checkProductionAbi` 성공, 기준 API 파일 변경 없음 |
| 전체 production build 및 Detekt | 성공, benchmark 제외; 전체 저장소 테스트 수행을 의미하지 않음 |
| 변경 내용 공백 검사 | `git diff --check origin/develop` 성공 |

최종 재검증 명령은 다음과 같다. DB 기반 테스트는 `--no-parallel`로 순차 실행했다.

```bash
./gradlew :bluetape4k-exposed-batch-core:build :bluetape4k-exposed-batch-jdbc:build :bluetape4k-exposed-batch-r2dbc:build :bluetape4k-exposed-batch:build --no-parallel --max-workers=4 --console=plain
./gradlew :bluetape4k-exposed-batch-core:jar --configuration-cache --configuration-cache-problems=fail --rerun-tasks --no-parallel --console=plain
./gradlew :bluetape4k-exposed-ktor:test :bluetape4k-exposed-spring-boot-common:test :bluetape4k-exposed-spring-boot-jdbc:test checkProductionAbi detekt -x :benchmark-exposed-benchmark:detekt --no-parallel --max-workers=4 --no-configuration-cache --console=plain
```

## 후속 검토 기준

assertion 변경은 더 구체적인 실패 진단을 제공하면서 기존 검증 의미를 유지해야 한다.
이 기준은 사용자 범위 `bluetape-kotlin-patterns`의 본문·testing reference·checklist에 반영했다.
스킬 원본과 적용 결과를 검증한 chezmoi commit은 `ad224728fc7fe4667c0367d0dbe5a050a8d423cc`이다.
건너뛴 테스트와 사전에 존재하던 로그 정책은 이번 변경의 통과 증거로 취급하지 않는다.
GitHub CI와 최종 병합 상태는 PR #878에서 별도로 확인한다.
