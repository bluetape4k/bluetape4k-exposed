# Issue #29 Trino 배치 쓰기 교훈

## 맥락

`exposed-trino`에는 Trino connector 쓰기가 항상 트랜잭션을 보장하거나 최적화된다고 오해시키지 않으면서, 더 안전한 배치 쓰기 경로를 문서화할 필요가 있었다.

## 교훈

- Trino `INSERT`는 SQL 수준 기능이지만 실제 쓰기 지원 여부는 구성된 catalog connector가 결정한다.
- 클라이언트 측 Exposed `batchInsert`는 connector 측 bulk loading이나 connector `write.batch-size` 튜닝과 다르다. 문서와 API 이름에서 이 계약을 분리해야 한다.
- Trino 쓰기에서 generated key 조회는 신뢰할 만한 기본값이 아니다. Trino 전용 helper는 `shouldReturnGeneratedValues=false`를 기본값으로 둬야 한다. Trino가 database-generated key를 노출하지 않아도 Exposed는 삽입 행을 반환할 수 있으므로, 명시적 `true` 경로도 테스트해야 한다.
- 청크 처리는 호출자 제어를 높이고 JDBC batch 하나의 크기를 제한하지만, partial-write 동작을 더 분명히 문서화해야 한다. 뒤 청크가 실패하면 앞 청크는 이미 보일 수 있다.
- 이 셸에서 `gnoq`는 독립 실행 binary가 아니라 `~/.zshrc` 함수다. `source ~/.zshrc; gnoq ...` 또는 `gno query ... --no-rerank`를 직접 사용한다.

## 검증

- GNO 조회로 로컬 지식 베이스에 있던 Trino autocommit/transaction 경고를 확인했다.
- 공식 Trino 문서로 `INSERT` 문법과 connector 의존 SQL statement 지원을 확인했다.
- 첫 대상 `InsertTest`는 7개 테스트를 통과했다.
- 로컬 코드 리뷰 subagent는 sandbox의 Trino connection EOF/connection 오류 때문에 `InsertTest --rerun-tasks`가 실패했다고 보고했다.
- 리더가 `./gradlew :exposed-trino:test --tests "io.bluetape4k.exposed.trino.insert.InsertTest" --rerun-tasks --console=plain`을 다시 실행했고, Claude 리뷰 통합 전 7개 테스트가 통과했다.
- Claude advisor는 P0/P1 차단 항목이 없다고 보고했으며, `shouldReturnGeneratedValues=true` 후속 커버리지, 더 단순한 chunk loop, 더 강한 partial-write 단언을 권고했다.
- 해당 리뷰 결과를 반영했다.
- `./gradlew :exposed-trino:test --tests "io.bluetape4k.exposed.trino.insert.InsertTest" --rerun-tasks --console=plain`은 리뷰 반영 후 8개 테스트를 통과했다.

## 후속 지침

- `trinoBatchInsert`를 bulk-loader protocol로 문서화하지 않는다. 이는 Exposed JDBC `batchInsert`를 제한된 범위로 감싼 wrapper다.
- 향후 connector가 실제 bulk write protocol을 제공하면, 이 helper의 의미를 넓히지 말고 connector 전용 API를 별도로 추가한다.
