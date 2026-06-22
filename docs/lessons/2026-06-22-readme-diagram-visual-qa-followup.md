# README Diagram Visual QA Follow-up

## Context

`docs/images/readme-diagrams`의 README용 SVG/PNG 다이어그램을
`bluetape4k-diagram` 체크리스트에 맞추는 과정에서 반복적인 시각 QA
누락이 있었다. XML 유효성, SVG 속성, 일부 자동 audit 통과만으로 완료를
보고하면 실제 PNG에서 보이는 결함을 놓칠 수 있다는 점이 이번 세션의 핵심
문제였다.

사용자가 직접 지적한 결함은 단일 파일 문제가 아니라 여러 다이어그램에
반복되는 패턴이었다. 따라서 이후 작업에서는 지적된 파일만 고치지 말고 같은
이름군, 같은 marker 정의, 같은 lane/card 배치, 같은 connector route 패턴을
항상 함께 검색해야 한다.

## Defect Classes and Fixes

### Lane title overlap

`exposed-*-diagram-02` 계열에서 lane 1/3 제목이 카드나 카드 그림자에
가려지는 문제가 있었다. 특히 `exposed-core-diagram-02`는 첫 두
lane의 카드 시작 y좌표가 lane 제목 baseline보다 위로 겹쳐 있었고,
`exposed-measured-diagram-02`, `exposed-tink-diagram-02`,
`exposed-r2dbc-tests-diagram-02`도 제목과 첫 카드 사이 간격이
부족했다.

해결은 카드만 이동하는 것이 아니라 전체 SVG 높이, viewBox, frame/canvas,
lane/group 높이, 카드 y좌표, connector path y좌표, 하단 note/footer 위치를
같이 늘리는 방식으로 했다. lane 제목과 첫 카드 사이 gap은 최소 56px 이상으로
확보했다. 좌표 변경 후에는 `marker-end` 같은 비좌표 속성이 손상되지 않는지
별도로 검사했다.

### Sequence diagram style drift

여러 `*-sequence-*` 다이어그램이 wiki best-practices 스타일과 달리 generic
flowchart처럼 보였고, call 간격도 좁았다. sequence diagram은 participant
header, vertical lifeline, 충분한 row height, 투명한 alt/else/loop 영역,
일관된 16x16 message arrowhead를 기준으로 다시 맞춰야 한다.

이후 sequence 수정 시에는 전체 height를 먼저 넉넉하게 잡고 message lane을
확보해야 한다. label과 call line이 겹치면 텍스트를 줄이거나 arrowhead를
숨기는 것이 아니라 diagram height와 call spacing을 키운다.

### Arrowhead rendering mismatch

SVG에서는 정상이어도 CairoSVG로 PNG를 만들면 dashed relationship의
arrowhead가 점선처럼 보이거나, arrowhead 색상이 line 색과 달리 검은색으로
보이는 문제가 있었다. class diagram의 UML hollow arrowhead는 특히 PNG
렌더링에서 marker stroke dash가 상속될 수 있다.

해결 원칙은 다음과 같다.

- UML hollow arrowhead는 18x16, sequence arrowhead는 16x16, primary
  flow/progression arrowhead는 14x14, secondary/static relationship은 10x10을
  기준으로 한다.
- 한 다이어그램 안에서 같은 의미의 arrowhead 크기를 섞지 않는다.
- marker child의 fill/stroke를 connector stroke 색상과 맞춘다.
- dashed line의 arrowhead는 marker만 믿지 말고 PNG에서 solid로 보이는지
  확인한다.
- marker 방식이 계속 실패하면 direct `polygon` 또는 `polyline` head로
  바꾸고 `stroke-dasharray="none"`을 head에 직접 둔다.

### Connector geometry and ports

connector는 수평/수직/rounded bend를 기본으로 해야 한다. `Q` command가 있다는
사실만으로 round corner가 통과되는 것은 아니다. PNG에서 실제로 둥근 corner로
보여야 한다. validate에서 갈라지는 선, cache/token cache로 들어가는 점선,
class diagram dependency line, JDBC/Redisson cache line 등에서 port 선택과
corner clearance가 반복적으로 문제가 되었다.

이후 connector 수정 시에는 다음 순서로 본다.

1. source/target card boundary에 90도로 붙는지 확인한다.
2. 같은 card side에 들어오고 나가는 port가 겹치지 않는지 확인한다.
3. 불필요한 교차는 port 변경이나 card 이동으로 먼저 해결한다.
4. L/H/V sharp bend 후보를 먼저 찾고, PNG에서 실제 round corner인지 확인한다.
5. card 위치를 바꾸면 모든 관련 connector path와 label도 같이 옮긴다.

### Icon and in-card text spacing

Spring Boot, Ktor, Redis, DB, BigQuery, DuckDB, Trino, StarRocks 등 실제
서비스나 기술 카드에는 wiki icon 또는 공식 icon을 사용해야 한다. icon을
넣은 뒤 title만 보지 말고 subtitle, subtext, metadata, tag까지 모두 icon과
겹치지 않는지 확인해야 한다.

카드 내부 text가 넘치거나 icon과 닿으면 카드 폭을 넓히고, 그 결과 connector
port, sibling card spacing, lane bounds, canvas/viewBox까지 같이 조정한다.
여백이 부족한 상태에서 카드만 밀어 넣으면 다이어그램 전체가 더 나빠진다.

### Visual QA process failure

가장 큰 실패는 자동 검사와 SVG 속성만 보고 "통과"라고 판단한 것이다. PNG가
README 독자가 보는 최종 artifact이므로, 다음부터는 최소한 다음 증거를 갖춘
뒤에만 완료를 보고한다.

- changed SVG XML validation
- CairoSVG PNG render
- geometry audit
- endpoint audit
- marker size/color audit when arrows changed
- dashed arrowhead audit for class diagrams
- icon duplicate/overlap audit when icons changed
- full-size PNG inspection for every touched/high-risk diagram
- contact sheet inspection for multi-diagram changes

## Current Follow-up Verification

이번 follow-up에서는 `exposed-*-diagram-02` 계열의 lane 제목 겹침을 다시
검사했고, gap이 부족한 7개 SVG/PNG를 수정했다.

- `exposed-cache-diagram-02`
- `exposed-core-diagram-02`
- `exposed-jdbc-diagram-02`
- `exposed-jdbc-redisson-diagram-02`
- `exposed-measured-diagram-02`
- `exposed-r2dbc-tests-diagram-02`
- `exposed-tink-diagram-02`

검증 증거:

- `xmllint --noout` on the 7 changed SVG files
- CairoSVG rendered the 7 PNG files
- lane-title/card gap recalculation had no failures
- `diagram-geometry-audit.py`: `geometry_failures=0`
- `diagram-endpoint-audit.py`: `endpoint_failures=0`
- `git diff --check`
- contact sheet inspection
- full-size PNG inspection for `core`, `measured`, `tink`, and `r2dbc-tests`

## Rule for Future Agents

사용자가 한 파일을 지적하면 그 파일만 고치지 말고 같은 결함 패턴을 전체
관련 diagram set에서 찾는다. 특히 README diagram은 "SVG가 맞다"가 아니라
"PNG에서 독자가 보는 결과가 맞다"가 완료 기준이다.

좌표를 자동 변환하는 스크립트를 사용할 때는 `path d` 속성만 변환하는지,
`marker-end`, `data-edge`, class 이름 같은 비좌표 속성을 건드리지 않았는지
즉시 검사한다. 자동화가 만든 변경은 반드시 diff와 PNG 눈검사로 다시
검증한다.
