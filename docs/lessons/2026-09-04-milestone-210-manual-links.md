# 2.1.0 안정판 매뉴얼 링크와 Spring Modulith README 버전 정렬

## 맥락

2.0.0 안정판을 승격한 뒤 저장소의 README와 Ktor 모듈 README가 중앙 매뉴얼의
이전 `/1.12/` 경로를 계속 가리켰다. Spring Modulith의 영문·한국어 README도
1.12.0 artifact를 예제에 남겨 최신 안정판과 개발선의 경계를 흐렸다.

## 원인

매뉴얼이 저장소 소유에서 중앙 사이트 소유로 이동했지만 README 링크를 갱신하는
계약이 없었다. 모듈 README의 URL과 dependency snippet도 릴리스 manifest의
`releaseRef` 또는 `manualVersion`에서 파생되지 않았다.

## 결정

- 안정판 링크는 중앙 manifest의 `publication.manualVersion`에 맞춰 `/2.0/`을 사용한다.
- Spring Modulith dependency 예제는 루트 README의 최신 안정판(`2.0.0`)과 EN/KO를
  동일하게 유지한다.
- `scripts/validate_stable_manual_links.rb`가 manifest 문서 목록, URL version, EN/KO
  링크 parity와 bounded HTTP 상태를 검사한다.
- `scripts/validate_spring_modulith_readme.rb`가 안정판 dependency와 양국어 parity를
  검사하며, 별도 `manual-links.yml` workflow에서 두 계약을 실행한다.

## 검증

- `ruby scripts/validate_stable_manual_links_test.rb` — 5 tests, 0 failures.
- `ruby scripts/validate_spring_modulith_readme_test.rb` — 2 tests, 0 failures.
- 중앙 `bluetape4k.github.io` manifest를 기준으로 전체 관련 README를 검사하고
  실제 `/2.0/` 링크의 bounded HTTP 상태를 확인했다.
- `git diff --check` 통과.

## 다음 변경자를 위한 경계

중앙 manifest의 `manualVersion` 또는 문서 경로를 바꾸면 README를 먼저 수정하고
`manual-links.yml`을 실행한다. `/1.12/` 같은 안정판 경로 literal을 다시 추가하지
말고 manifest가 허용하는 경로를 사용한다. Snapshot dependency는 안정판 예제와
섞지 말고 현재 catalog의 개발선임을 명시한다.
