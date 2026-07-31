# README Mermaid SVG Infographics

## 배경

README file은 live Mermaid diagram을 사용했습니다. documentation presentation에는
sequence diagram을 editable Mermaid source로 유지하면서 stable pastel SVG infographic
asset이 필요했습니다.

## 결정

sequence가 아닌 모든 README Mermaid block을 `docs/images/readme-diagrams/` 아래 SVG로
render하고 해당 block만 relative image link로 교체합니다.

## 결과

non-sequence diagram의 checked-in SVG asset을 생성했습니다. `sequenceDiagram` block은
Mermaid code block으로 남습니다.

## 검증

Mermaid CLI 11.14.0으로 SVG asset을 render하고 SVG link/file count, 남은 non-sequence
README Mermaid block 0건을 확인했으며 `git diff --check`을 실행했습니다.

## 향후 지침

먼저 render하고 모든 SVG가 존재한 뒤에만 README link를 확정합니다. repository-wide
documentation rewrite에서는 worktree와 build output을 제외합니다.
