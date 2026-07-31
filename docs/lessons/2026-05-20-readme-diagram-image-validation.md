# README Diagram Image Validation

## 배경

README diagram asset은 reuse용 SVG source를 보존하는 pastel infographic PNG image로
재생성되었습니다.

## 결정

README file에는 PNG embed를 쓰고 SVG asset은 옆에 보관하며 diagram label은
English-only로 유지합니다. class diagram은 UML compartment와 visible inheritance stem을
유지해야 하고 sequence diagram은 note가 message를 덮지 않도록 vertical로 확장해야 합니다.

## 결과

exposed README diagram이 PNG file로 재생성·연결되었습니다. `exposed-jdbc-redisson`의
stale Mermaid tail을 제거했고 `exposed-core` ID-table hierarchy image는 inheritance
arrow가 triangle marker만이 아니라 visible line segment를 보이도록 조정했습니다.

## 검증

- Full regeneration: `rendered=188`, `missing=[]`.
- README image link: `missing=0`.
- Local SVG image embed: `0`.
- Mermaid residue: `0`.
- Asset count: `png=155`, `svg=155`.
- Shape sanity check: `shapeCandidates=0`.
- Whitespace check: `git diff --check`.

## 향후 지침

inheritance 또는 realization arrow가 marker-only visual로 무너지면 class diagram을
수락하지 않습니다. review 전에 class row spacing을 늘립니다.
