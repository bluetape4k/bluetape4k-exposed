# README Diagram Geometry Audit

## Context

`bluetape4k-exposed` README diagram refresh는 루트와 모듈 README 전반의
SVG/PNG 자산을 다시 만든 큰 문서 작업이었다. 중간 검수에서 연결선, label,
card 여백, sequence `alt` 영역, chart font 등이 반복적으로 지적됐다.

## Decision or Finding

PNG 눈검수만으로는 충분하지 않다. SVG에는 `data-edge`와 `data-node`를
일관되게 남겨야 하며, 자동 검증이 skip 없이 모든 연결선을 확인할 수 있어야
한다.

검증 기준은 다음처럼 나누는 편이 안전하다.

- 일반 diagram/flow/chart: `data-edge` 시작점과 끝점이 실제 card 또는
  layer 경계에 붙는지 검사한다.
- sequence diagram: card 경계가 아니라 participant lifeline x좌표에
  message가 붙는지 검사한다.
- label은 자신이 설명하는 선 가까이에 두되, 텍스트 anchor가 선 위에 놓이면
  실패로 본다.
- 텍스트 폭은 rough estimator로 후보를 먼저 찾고, 확대 PNG로 실제 overflow를
  확인한다.

## Outcome

전체 README-facing SVG 108개를 다시 검토했다. 실제 수정은 작았지만 검증성을
크게 높였다.

- ClickHouse OLTP/OLAP topology의 `transaction(pgDb)` /
  `transaction(chDb)` label을 수직 연결선에서 분리했다.
- ClickHouse architecture의 하단 server contract pill을 넓혀 긴 문장이
  card 안에 들어가도록 했다.
- measured DSL coverage의 긴 문구를 줄여 text/card 폭 여유를 확보했다.
- ClickHouse example, JDBC tests, measured diagrams의 `data-edge` 이름을
  실제 `data-node`와 맞춰 향후 endpoint audit이 skip 없이 동작하도록 했다.

## Verification

- `xmllint --noout docs/images/readme-diagrams/*.svg docs/images/readme-charts/*.svg`
- README local image link check
- forbidden style scan for `Comic Sans`, `Comic Neue`, `Graphviz`,
  `markerUnits="strokeWidth"`, stale Mermaid wording, opaque sequence alt fills
- non-sequence endpoint audit: 199 edges, skipped 0, failures 0
- sequence lifeline endpoint audit: 20 edges, skipped 0, failures 0
- label-line proximity audit: 0 candidates
- severe text overflow audit: 0 candidates
- CairoSVG re-render and enlarged PNG inspection for the touched diagrams
- `git diff --check`

## Future Guidance

For large README diagram work, do not finish with only contact sheets. Require a
geometry audit that reports checked edge count, skipped edge count, endpoint
failures, label-line proximity candidates, and severe text candidates. A skipped
edge is not neutral; it means the SVG metadata is too weak for future review and
should be fixed unless the diagram has no meaningful connector semantics.
