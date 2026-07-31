# README Diagram Infographics

## 배경

README file은 architecture, class, sequence, ERD 등의 diagram에 Mermaid code block을
사용했습니다. workspace 전체의 visual direction은 reuse를 위해 SVG source asset을
보존하는, 검토된 pastel infographic PNG로 바뀌었습니다.

## 결정

README Mermaid block을 생성된 PNG image link로 교체하고 PNG file 옆에 일치하는 SVG
source를 저장합니다. diagram text는 English-only로 두고 큰 label에는 Architects Daughter,
detail text에는 Comic Mono를 사용하며 architecture, class, sequence, ERD diagram에
diagram-specific layout을 사용합니다.

## 결과

`bluetape4k.github.io/docs/readme-diagram-samples`의 shared 2026-05-19 style guide로
README diagram을 render했습니다. root README asset은 존재할 때 repo-local asset placement
rule을 따릅니다.

## 검증

cross-repository conversion pass에서 rsvg-convert로 PNG/SVG asset을 생성하고 README
link를 확인했습니다.

## 향후 지침

README diagram은 편집용 SVG source를 갖는 PNG embed로 유지합니다. visual consistency가
중요할 때 raw Mermaid 또는 단순 Mermaid theme recoloring으로 되돌아가지 않습니다.
