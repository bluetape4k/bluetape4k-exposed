# README Diagram Layout Fix

## 배경

후속 visual QA가 generated README diagram의 두 layout defect를 발견했습니다.

- 일부 architecture connector는 arrow head만 보이는 매우 짧은 line segment로 render됨
- sequence participant header label이 header box 상단 쪽으로 치우침

관련 sequence 문제도 수정했습니다. self-call은 이전에 zero-length arrow로 render되어
독립된 arrow head처럼 보였습니다.

## 결정

기존 diagram style은 유지하고 generated SVG/PNG asset의 geometry만 업데이트합니다.
architecture connector line segment는 인접 card 사이 visible gap을 채워야 합니다.
sequence participant label은 architecture card와 같은 vertical-centering baseline을
사용하고 self-call은 zero-length line 대신 작은 loop로 render합니다.

## 검증

- README image link check: missing=0, localSvgImageLinks=0, mermaidResidue=0
- PNG/SVG shape check: shapeCandidates=0
- architecture short connector check: shortArch=0
- sequence header alignment check: seqTop=0
- sequence zero-length arrow check: zeroSeq=0
- `git diff --check`
- exposed root architecture와 representative sequence diagram의 visual sample 검토

## 향후 지침

SVG가 문법적으로 유효해도 arrow head-only connector는 failed rendering으로 봅니다.
PR 생성 전 geometry check는 architecture connector length, sequence header baseline,
sequence self-call arrow를 포함해야 합니다.
