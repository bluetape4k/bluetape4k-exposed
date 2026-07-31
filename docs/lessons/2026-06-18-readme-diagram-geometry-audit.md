# README 다이어그램 기하 감사

## 배경

`bluetape4k-exposed` README diagram refresh는 루트와 모듈 README 전반의
SVG/PNG 자산을 다시 만든 큰 문서 작업이었다. 중간 검수에서 연결선, label,
card 여백, sequence `alt` 영역, chart font 등이 반복적으로 지적됐다.

## 결정 또는 발견

PNG 육안 검수만으로는 충분하지 않습니다. SVG에는 `data-edge`와 `data-node`를
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

## 결과

README에 노출되는 SVG 108개를 모두 다시 검토했습니다. 실제 수정은 작았지만
검증 가능성을 크게 높였습니다.

- ClickHouse OLTP/OLAP topology의 `transaction(pgDb)` /
  `transaction(chDb)` label을 수직 연결선에서 분리했습니다.
- ClickHouse architecture의 하단 server contract pill을 넓혀 긴 문장이
  card 안에 들어가게 했습니다.
- measured DSL coverage의 긴 문구를 줄여 text/card 폭 여유를 확보했습니다.
- ClickHouse example, JDBC tests, measured diagrams의 `data-edge` 이름을
  실제 `data-node`와 맞춰 이후 endpoint audit이 skip 없이 동작하도록 했습니다.

## 검증

- `xmllint --noout docs/images/readme-diagrams/*.svg docs/images/readme-charts/*.svg`
- README 로컬 이미지 링크 검사
- `Comic Sans`, `Comic Neue`, `Graphviz`, `markerUnits="strokeWidth"`, 오래된
  Mermaid 문구, 불투명 sequence alt fill을 대상으로 한 금지 스타일 검사
- non-sequence endpoint 감사: edge 199개, skipped 0개, 실패 0개
- sequence lifeline endpoint 감사: edge 20개, skipped 0개, 실패 0개
- label-line 근접 감사: 후보 0개
- 심각한 텍스트 overflow 감사: 후보 0개
- 변경한 다이어그램의 CairoSVG 재렌더링 및 확대한 PNG 검사
- `git diff --check`

## 향후 지침

대규모 README 다이어그램 작업은 contact sheet만으로 끝내지 마세요. 검사한 edge 수,
skipped edge 수, endpoint 실패, label-line 근접 후보, 심각한 텍스트 후보를 보고하는
geometry 감사를 요구해야 합니다. skipped edge는 중립적 결과가 아닙니다. 이는 SVG
메타데이터가 이후 검토에 너무 약하다는 뜻이므로, 의미 있는 connector 의미론이 없는
다이어그램이 아니라면 수정해야 합니다.
