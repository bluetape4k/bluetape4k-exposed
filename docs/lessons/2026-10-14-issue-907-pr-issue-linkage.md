# 이슈 #907 — PR의 이슈 연결과 메타데이터는 생성 전에 확정한다

## 맥락

`scripts/ci/__pycache__/` 제외 변경을 준비하면서 연결할 GitHub 이슈와 PR 메타데이터를 먼저 확정하지 않았다. 사용자가 PR 메타데이터와 이슈 연결 누락을 지적했다. 저장소에는 이슈와 PR의 담당자, 레이블, 마일스톤, `Closes` 연결을 GitHub에서 확인하라는 기존 규칙([이슈 #816 교훈](2026-09-07-issue-816-tenant-jdbc-registry.md))이 있었지만, 이번 작업 순서에 적용하지 못했다.

작업 당시 열린 이슈 중 CI 검증기의 Python 바이트코드 캐시 제외를 같은 완료 기준으로 다루는 이슈를 확인하지 못해 [이슈 #907](https://github.com/bluetape4k/bluetape4k-exposed/issues/907)을 `2.1.0` 마일스톤에 등록했다. 당시 검색 명령과 검색어는 기록하지 않아 이 검색은 재현할 수 없다. 이슈는 `debop`에게 할당하고 `ci`, `tech-debt` 레이블을 붙였다.

## 결정

- PR을 만들기 전에 기존 이슈를 검색하고, 연결할 이슈와 마일스톤·담당자·레이블을 확정한다. 중복이 없으면 변경 범위를 설명하는 이슈를 먼저 등록한다.
- PR 본문에 `Closes #<issue-number>`를 넣고 이슈의 마일스톤·담당자·레이블을 PR에도 맞춘다.
- PR 생성 후 `closingIssuesReferences`와 담당자·레이블·마일스톤을 GitHub에서 조회해 이슈와 대조한다. 대조를 마치기 전에는 CI·리뷰 단계로 넘어가지 않는다.

다음 명령은 저장소와 이슈를 고정한다. PR 생성 후 `PR`과 `EXPECTED_HEAD`에 실제 값을 넣어 실행하면 이슈 연결, head, 담당자, 레이블, 마일스톤이 모두 일치할 때만 성공한다.

```bash
REPO=bluetape4k/bluetape4k-exposed
ISSUE=907
PR='<PR 번호>'
EXPECTED_HEAD='<현재 PR head SHA>'

ISSUE_JSON="$(gh issue view "$ISSUE" --repo "$REPO" --json assignees,labels,milestone)"
PR_JSON="$(gh pr view "$PR" --repo "$REPO" --json assignees,labels,milestone,headRefOid,closingIssuesReferences)"
jq -en --argjson issue "$ISSUE_JSON" --argjson pr "$PR_JSON" \
  --argjson issue_number "$ISSUE" --arg expected_head "$EXPECTED_HEAD" '
  ($pr.headRefOid == $expected_head) and
  any($pr.closingIssuesReferences[]?; .number == $issue_number) and
  (($pr.assignees | map(.login) | sort) == ($issue.assignees | map(.login) | sort)) and
  (($pr.labels | map(.name) | sort) == ($issue.labels | map(.name) | sort)) and
  ($issue.milestone != null) and
  ($pr.milestone.title == $issue.milestone.title)
'
```

## 결과와 검증

- `gh issue view 907 --json number,title,state,assignees,labels,milestone,url`: 이슈 #907이 `OPEN`이고 `2.1.0`, `debop`, `ci`, `tech-debt` 메타데이터를 갖는 것을 확인했다.
- `gh pr view 906 --json closingIssuesReferences`: GitHub CLI가 이슈 연결을 직접 반환하는 것을 확인했다. 이 명령은 이번 PR의 연결 증거를 대신하지 않는다.

## 놓친 점과 재발 방지

기존 교훈이 이미 요구한 메타데이터 검증을 PR 생성 전 순서에 넣지 않은 것이 원인이었다. 앞으로 이슈 연결과 메타데이터 확인을 PR 생성의 선행 조건으로 두고, 생성 후 GitHub 조회 결과까지 다시 확인해야 다음 단계로 진행한다.
