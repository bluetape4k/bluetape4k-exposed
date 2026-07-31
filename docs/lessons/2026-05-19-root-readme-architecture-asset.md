# Root README Architecture Asset

## 배경

root README architecture section은 English와 Korean README file 모두에서 Mermaid
diagram을 사용했습니다.

## 결정

root README visual asset이 `docs/assets/`에 있고 localized README가 공유한다는
repo-local rule을 따라 Mermaid block을 `docs/assets/` 아래 shared SVG asset 하나로
교체합니다.

## 결과

`README.md`와 `README.ko.md`는 이제 `docs/assets/exposed-architecture.svg`를 embed합니다.

## 검증

`xmllint --noout`으로 SVG를 검증하고 두 README link가 shared asset으로 resolve됨과
architecture section에 Mermaid block이 남지 않았음을 확인했습니다.

## 향후 지침

text-heavy README diagram은 GitHub rendering에서 module name과 API term이 읽히도록
generated bitmap image보다 deterministic SVG asset을 우선합니다.
