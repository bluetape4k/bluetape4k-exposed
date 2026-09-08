"""활성화된 소비 모듈의 테스트가 생략되지 않고 성공했는지 검사한다."""

import json
import os
import sys

from validate_ci_matrix_contract import MODULE_OUTPUTS


def required_job_errors(needs: dict, event_name: str) -> list[str]:
    """GitHub needs 결과에서 요구되는 테스트의 누락·실패·skip을 반환한다."""
    changes = needs.get("changes", {})
    if changes.get("result") != "success":
        return ["changes job must succeed before resolving required consumers"]
    outputs = changes.get("outputs", {})
    if outputs.get("docs-only") == "true":
        return []
    all_modules = (
        outputs.get("all-modules") == "true" or event_name == "workflow_dispatch"
    )
    aliases = {
        "jdbc": ("test-jdbc-h2",),
        "r2dbc": ("test-r2dbc-h2",),
        "ktor": ("test-ktor-exposed", "test-ktor-driver-timeout"),
    }
    errors = []
    for output in MODULE_OUTPUTS:
        if not all_modules and outputs.get(output) != "true":
            continue
        for job in aliases.get(output, (f"test-{output}",)):
            result = needs.get(job, {}).get("result", "missing")
            if result != "success":
                errors.append(f"Required consumer {job}: {result}")
    return errors


def main() -> int:
    errors = required_job_errors(
        json.loads(os.environ["NEEDS_JSON"]), os.environ["GITHUB_EVENT_NAME"]
    )
    for error in errors:
        print(error, file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
