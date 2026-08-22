#!/usr/bin/env python3
"""Check the technical, numeric, table, heading, and link parity of EN/KO evidence docs."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
TABLE_SEPARATOR = re.compile(r"^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?$")
LINK = re.compile(r"\[[^\]]+\]\(([^)]+)\)")
CODE = re.compile(r"`([^`]+)`")
FENCED = re.compile(r"```[^\n]*\n(.*?)```", re.DOTALL)
TECHNICAL = re.compile(
    r"(?:[A-Za-z_][A-Za-z0-9_.:/-]*[A-Za-z0-9_]|sha256:[0-9a-f]{64}|\b\d+(?:\.\d+)+(?:-[A-Za-z0-9.]+)?)"
)
NUMBER = re.compile(r"(?<![A-Za-z])\d+(?:[,.]\d+)*(?:\.\d+)?(?:%|\b)")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("english", type=Path)
    parser.add_argument("korean", type=Path)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def fail(message: str) -> "NoReturn":
    raise SystemExit(f"validate_readme_parity: {message}")


def read(path: Path) -> str:
    if not path.is_file() or path.is_symlink():
        fail(f"document is not a regular file: {path}")
    return path.read_text(encoding="utf-8")


def headings(text: str) -> list[tuple[int, str]]:
    return [(len(match.group(1)), match.group(2)) for line in text.splitlines() if (match := HEADING.match(line))]


def table_rows(text: str) -> list[str]:
    rows = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("|") and stripped.endswith("|") and not TABLE_SEPARATOR.match(stripped):
            rows.append(stripped)
    return rows


def table_cells(text: str) -> list[list[str]]:
    cells: list[list[str]] = []
    for line in table_rows(text):
        cells.append([cell.strip() for cell in line.strip("|").split("|")])
    return cells


def links(text: str) -> list[str]:
    return LINK.findall(text)


def normalized_links(text: str) -> list[str]:
    return [re.sub(r"\.ko(?=\.(?:png|svg)$)", "", target) for target in links(text)]


def validate_link_targets(document: Path, targets: list[str]) -> None:
    for target in targets:
        if target.startswith(("http://", "https://", "#")):
            continue
        target_path = (document.parent / target).resolve()
        if not target_path.is_file() or target_path.is_symlink():
            fail(f"{document.name} link target is not a regular file: {target}")


def technical_tokens(text: str) -> list[str]:
    tokens: list[str] = []
    fenced_blocks = FENCED.findall(text)
    without_fences = FENCED.sub("", text)
    for block in fenced_blocks:
        tokens.extend(TECHNICAL.findall(block))
        tokens.extend(NUMBER.findall(block))
    for match in CODE.findall(without_fences):
        tokens.extend(TECHNICAL.findall(match))
        tokens.extend(NUMBER.findall(match))
    for target in normalized_links(text):
        tokens.append(f"link:{target}")
    for line in table_rows(text):
        tokens.extend(TECHNICAL.findall(line))
        tokens.extend(NUMBER.findall(line))
    return sorted(set(tokens))


def heading_contract_tokens(values: list[tuple[int, str]]) -> list[list[str]]:
    """Compare stable technical heading tokens, excluding translated prose."""
    result: list[list[str]] = []
    for _, heading in values:
        tokens = CODE.findall(heading)
        tokens.extend(NUMBER.findall(heading))
        tokens.extend(re.findall(r"\b[A-Z][A-Z0-9_]{2,}\b", heading))
        result.append(sorted(set(tokens)))
    return result


def main() -> None:
    args = parse_args()
    english = read(args.english)
    korean = read(args.korean)
    en_headings = headings(english)
    ko_headings = headings(korean)
    en_tables = table_rows(english)
    ko_tables = table_rows(korean)
    en_table_cells = table_cells(english)
    ko_table_cells = table_cells(korean)
    en_links = normalized_links(english)
    ko_links = normalized_links(korean)
    validate_link_targets(args.english, links(english))
    validate_link_targets(args.korean, links(korean))
    en_tokens = technical_tokens(english)
    ko_tokens = technical_tokens(korean)
    result = {
        "english": args.english.name,
        "korean": args.korean.name,
        "headingCount": len(en_headings),
        "headingLevels": [level for level, _ in en_headings],
        "tableRowCount": len(en_tables),
        "linkCount": len(en_links),
        "technicalNumericTokensEqual": en_tokens == ko_tokens,
        "linksEqual": en_links == ko_links,
        "headingLevelsEqual": [level for level, _ in en_headings] == [level for level, _ in ko_headings],
        "tableRowCountEqual": len(en_tables) == len(ko_tables),
        "tableCellsEqual": en_table_cells == ko_table_cells,
        "headingTechnicalTokensEqual": heading_contract_tokens(en_headings) == heading_contract_tokens(ko_headings),
        "linkTargetsExist": True,
    }
    result["ok"] = all(
        (
            result["technicalNumericTokensEqual"],
            result["linksEqual"],
            result["headingLevelsEqual"],
            result["tableRowCountEqual"],
            result["tableCellsEqual"],
            result["headingTechnicalTokensEqual"],
            len(en_headings) == len(ko_headings),
            len(en_links) == len(ko_links),
            result["linkTargetsExist"],
        )
    )
    encoded = json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if not result["ok"]:
        fail(encoded.rstrip())
    if args.output:
        if args.output.exists() or args.output.is_symlink():
            fail(f"output already exists: {args.output}")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding="utf-8")
    sys.stdout.write(encoded)


if __name__ == "__main__":
    main()
