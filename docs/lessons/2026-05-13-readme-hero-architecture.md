# README Hero 및 아키텍처 갱신

## 배경

루트 README에는 기능과 module 목록은 있었지만 entrypoint에서 전체 repository
architecture를 보여주지 않았다.

## 결정

생성한 Exposed workbench 이미지를 `docs/assets/exposed-workbench.png`에 저장하고,
core repository, cross-cutting module, dialect extension, Spring Boot 4 통합을
포함하는 루트 Mermaid architecture diagram을 추가한다.

## 결과

두 README locale 모두 이제 quick-start 예제보다 먼저 project purpose, feature
scope, visual hero, repository architecture를 제시한다.

## 검증

- 생성한 asset이 `docs/assets` 아래 PNG로 존재하는지 확인했다.
- 두 README locale이 공유 image path를 참조하는지 검증했다.

## Future Guidance

When adding a root-level module family, update the README architecture diagram,
`AGENTS.md`, and `CLAUDE.md` together.
