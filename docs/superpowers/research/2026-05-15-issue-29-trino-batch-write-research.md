# Trino Batch Write 조사 - Issue #29

- Issue: https://github.com/bluetape4k/bluetape4k-exposed/issues/29
- 범위: Trino `INSERT`, connector write capability, 트랜잭션 및 batch write가
  `exposed-trino`에 미치는 영향.
- 날짜: 2026-05-15

## 로컬 지식 조회

비대화형 shell에서 `gnoq`를 직접 실행할 수 없었다. `~/.zshrc`에 정의되어
있었기 때문이다. `source ~/.zshrc`를 사용하고 `gno query ... --no-rerank`를
직접 실행해 다시 시도했다.

관련 GNO 결과:

- `gno://wiki/pages/database-dialects.md`는 이 module에서 Trino가
  autocommit-only라고 기록하며 Trino Memory connector가 `BEGIN` / `COMMIT` /
  `ROLLBACK`을 지원하지 않는다고 설명한다.

## 공식 문서 조사 결과

- Trino `INSERT` syntax는 단일 행 및 다중 행 `VALUES` 예제를 포함해
  `INSERT INTO table_name ... query`를 지원한다:
  https://trino.io/docs/current/sql/insert.html
- Trino SQL statement support는 connector에 따라 달라진다. 문서는 source
  system capability가 SQL 지원을 제한할 수 있으며 statement 세부 사항은
  connector별로 문서화한다고 설명한다:
  https://trino.io/docs/current/language/sql-support.html
- Trino transaction은 SQL statement지만 대부분의 connector는 transaction을
  지원하지 않는다:
  https://trino.io/docs/current/language/sql-support.html
- connector에서 `INSERT`를 지원하려면 `beginInsert()`, `finishInsert()`,
  `ConnectorPageSinkProvider` 같은 connector SPI 지원이 필요하다:
  https://trino.io/docs/current/develop/insert.html
- PostgreSQL/MySQL 같은 JDBC-style connector는 connector 측
  `write.batch-size`와 non-transactional insert option을 문서화한다. 이는
  Trino catalog 설정이며 client의 `PreparedStatement.executeBatch`가
  connector bulk-loader protocol이라는 보장은 아니다.

## 결정

다음 동작을 하는 작은 `trinoBatchInsert` helper를 추가한다.

- Exposed JDBC `batchInsert`를 감싼다.
- caller data를 명시적으로 chunk로 나눈다.
- generated-key retrieval 기본값을 disabled로 둔다.
- connector 의존 write support와 partial-write risk를 문서화한다.
- 향후 connector별 bulk-loader API와 분리된 상태를 유지한다.
