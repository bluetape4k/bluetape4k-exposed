"""Report Kover applicability only after successful change detection."""

from __future__ import annotations

import os
import sys

NOT_APPLICABLE_SUMMARY = (
    "Kover coverage: N/A — coverage report를 생성할 테스트 job이 선택되지 않았습니다."
)


def coverage_summary(coverage_required: str | None, changes_result: str) -> str | None:
    if changes_result != "success":
        return None
    if coverage_required == "true":
        return None
    if coverage_required == "false":
        return NOT_APPLICABLE_SUMMARY
    raise ValueError(
        "coverage-enabled must be 'true' or 'false' after changes detection "
        f"succeeds; got {coverage_required!r}"
    )


def main() -> int:
    try:
        summary = coverage_summary(
            os.environ.get("COVERAGE_REQUIRED"),
            os.environ.get("CHANGES_RESULT", ""),
        )
    except ValueError as error:
        print(error, file=sys.stderr)
        return 1

    if summary is not None:
        summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary_path:
            with open(summary_path, "a", encoding="utf-8") as summary_file:
                summary_file.write(f"{summary}\n")
        else:
            print(summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
