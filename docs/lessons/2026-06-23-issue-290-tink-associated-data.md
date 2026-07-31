# 교훈: 이슈 #290 Tink associated data 바인딩

## 배경

Tink AEAD와 DAEAD API는 associated data를 인증하지만, Exposed Tink column transformer는 이전에 associated data 없이 encrypt/decrypt helper를 호출했습니다. 따라서 한 암호화 column의 ciphertext를 같은 key를 사용하는 호환 column으로 복사해도 decrypt될 수 있었습니다.

## 결정

기본 table extension helper는 이제 ciphertext를 안정적인 domain에 바인딩합니다.

```text
bluetape4k-exposed-tink:v1:<tableName>:<columnName>
```

public `TinkColumnAssociatedDataProvider` 계약은 사용자가 더 강한 domain을 제공하게 하며, `TinkColumnAssociatedDataProvider.Empty`는 legacy migration에만 사용할 수 있습니다.

## 안전장치

associated-data domain을 명시적으로 결정하지 않은 Tink encrypt/decrypt 호출로 새 암호화 column helper 경로를 추가하지 마세요. DAEAD에서는 하나의 query value가 candidate row 전체에서 공유 ciphertext를 더는 만들 수 없으므로 row-scoped associated data가 일반 equality search를 깨뜨린다는 점을 기억해야 합니다.

## 테스트

회귀 suite는 두 case를 모두 유지해야 합니다.

- Helper 기본값: column/table 간에 복사한 ciphertext가 decrypt에 실패합니다.
- Direct 등록: 명시적인 associated data도 복사한 ciphertext를 거부합니다.
