#!/usr/bin/env python3
"""Generate root README module relationship diagrams.

The generator is intentionally small and deterministic. It validates the root
README module table against the current Gradle module layout, then writes the
SVG sources consumed by the rendered PNG assets.
"""

from __future__ import annotations

import html
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/images/readme-diagrams"

README_MODULE_RE = re.compile(r"^\| `([^`]+)` \|")
INCLUDE_MAPPED_RE = re.compile(r'includeMappedModule\("([^"]+)",\s*"([^"]+)"\)')


GROUPS = [
    {
        "key": "foundation",
        "title": "Foundation",
        "caption": "Exposed DSL extensions and DAO lifecycle helpers",
        "color": "#E8F3FF",
        "stroke": "#75A9E8",
        "modules": ["exposed-core", "exposed-dao"],
    },
    {
        "key": "repositories",
        "title": "Repository engines",
        "caption": "Blocking JDBC and coroutine-first R2DBC access",
        "color": "#EAF7EF",
        "stroke": "#69B888",
        "modules": ["exposed-jdbc", "exposed-r2dbc", "exposed-jdbc-tests", "exposed-r2dbc-tests"],
    },
    {
        "key": "cache",
        "title": "Cache integrations",
        "caption": "Common cache abstraction plus JDBC/R2DBC adapters",
        "color": "#E9F7F6",
        "stroke": "#55B5AF",
        "modules": [
            "exposed-cache",
            "exposed-jdbc-caffeine",
            "exposed-jdbc-lettuce",
            "exposed-jdbc-redisson",
            "exposed-r2dbc-caffeine",
            "exposed-r2dbc-lettuce",
            "exposed-r2dbc-redisson",
        ],
    },
    {
        "key": "columns",
        "title": "Column codecs",
        "caption": "JSON, encryption, and measured-unit column mappings",
        "color": "#FDECEF",
        "stroke": "#DB7890",
        "modules": ["exposed-jackson2", "exposed-jackson3", "exposed-fastjson2", "exposed-tink", "exposed-measured"],
    },
    {
        "key": "dialects",
        "title": "Dialects and analytics",
        "caption": "Database-specific SQL and connector helpers",
        "color": "#FFF3D9",
        "stroke": "#D9AA4D",
        "modules": [
            "exposed-postgresql",
            "exposed-mysql8",
            "exposed-bigquery",
            "exposed-clickhouse",
            "exposed-trino",
            "exposed-duckdb",
            "exposed-timefold-solver-persistence",
        ],
    },
    {
        "key": "spring",
        "title": "Spring Boot",
        "caption": "Auto-configuration and application integration",
        "color": "#F0ECFF",
        "stroke": "#9B85D9",
        "modules": [
            "exposed-spring-boot-jdbc",
            "exposed-spring-boot-r2dbc",
            "exposed-spring-boot-batch",
            "exposed-spring-modulith",
        ],
    },
]

BOM = "bluetape4k-exposed-bom"
README_MODULES = [m for group in GROUPS for m in group["modules"]]


def esc(value: str) -> str:
    return html.escape(value, quote=True)


def read_readme_modules() -> list[str]:
    modules: list[str] = []
    in_table = False
    for line in (ROOT / "README.md").read_text(encoding="utf-8").splitlines():
        if line == "| Module | Description |":
            in_table = True
            continue
        if in_table:
            if not line.startswith("|"):
                break
            match = README_MODULE_RE.match(line)
            if match:
                modules.append(match.group(1))
    return modules


def gradle_modules() -> set[str]:
    modules = {BOM}

    exposed_dir = ROOT / "exposed"
    for child in sorted(exposed_dir.iterdir()):
        if child.name == BOM:
            continue
        if (child / "build.gradle.kts").is_file():
            modules.add(child.name)

    examples_dir = ROOT / "examples"
    for child in sorted(examples_dir.iterdir()):
        if (child / "build.gradle.kts").is_file():
            modules.add(f"examples-{child.name}")

    settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
    for _, project_name in INCLUDE_MAPPED_RE.findall(settings):
        modules.add(project_name.removeprefix("bluetape4k-"))
    return modules


def validate_source() -> None:
    readme_modules = read_readme_modules()
    if readme_modules != README_MODULES:
        missing = sorted(set(readme_modules) - set(README_MODULES))
        stale = sorted(set(README_MODULES) - set(readme_modules))
        raise SystemExit(f"README module table drift. missing={missing} stale={stale}")

    source_modules = gradle_modules()
    expected = {BOM, *README_MODULES, "examples-exposed-clickhouse-oltp-olap"}
    missing_from_source = sorted(expected - source_modules)
    if missing_from_source:
        raise SystemExit(f"Modules missing from Gradle source layout: {missing_from_source}")


def text(x: float, y: float, value: str, cls: str = "label", anchor: str = "start") -> str:
    return f'<text class="{cls}" x="{x:g}" y="{y:g}" text-anchor="{anchor}">{esc(value)}</text>'


def rounded(x: float, y: float, w: float, h: float, fill: str, stroke: str, rx: int = 14, cls: str = "") -> str:
    class_attr = f' class="{cls}"' if cls else ""
    return f'<rect{class_attr} x="{x:g}" y="{y:g}" width="{w:g}" height="{h:g}" rx="{rx}" fill="{fill}" stroke="{stroke}" stroke-width="2"/>'


def module_pill(x: float, y: float, w: float, label: str) -> str:
    display = {
        "exposed-timefold-solver-persistence": "timefold persistence",
        "exposed-spring-modulith": "spring modulith",
    }.get(label, label.removeprefix("exposed-"))
    return (
        f'<rect x="{x:g}" y="{y:g}" width="{w:g}" height="26" rx="7" fill="#FFFFFF" '
        'opacity="0.83" stroke="#D7E2EC"/>'
        + text(x + w / 2, y + 18, display, "label", "middle")
    )


def header(width: int, height: int, title: str, subtitle: str) -> list[str]:
    return [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" role="img" aria-labelledby="title desc">',
        f'<title id="title">{esc(title)}</title><desc id="desc">{esc(subtitle)}</desc>',
        "<defs>",
        '<filter id="shadow" x="-8%" y="-8%" width="116%" height="116%"><feDropShadow dx="0" dy="5" stdDeviation="6" flood-color="#203040" flood-opacity="0.09"/></filter>',
        '<marker id="arrow-blue" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M1,1 L8,4.5 L1,8 Z" fill="#557FAF"/></marker>',
        '<marker id="arrow-green" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M1,1 L8,4.5 L1,8 Z" fill="#4F8F6C"/></marker>',
        '<marker id="arrow-purple" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M1,1 L8,4.5 L1,8 Z" fill="#7B65B6"/></marker>',
        '<style>.canvas{fill:#F6F9FC}.frame{fill:#FFFFFF;stroke:#D7E2EC;stroke-width:2}.title{font-family:"Architects Daughter",cursive;font-size:42px;fill:#22344A;font-weight:400}.subtitle{font-family:"Comic Mono",monospace;font-size:17px;fill:#536476;font-weight:400}.group{filter:url(#shadow)}.gtitle{font-family:"Architects Daughter",cursive;font-size:24px;fill:#22344A;font-weight:400}.label{font-family:"Comic Mono",monospace;font-size:14px;fill:#34465B;font-weight:400}.small{font-family:"Comic Mono",monospace;font-size:12px;fill:#627184;font-weight:400}.count{font-family:"Architects Daughter",cursive;font-size:26px;fill:#22344A;font-weight:400}.edge{fill:none;stroke-width:3;stroke-linecap:round;stroke-linejoin:round}</style>',
        "</defs>",
        f'<rect class="canvas" width="{width}" height="{height}"/>',
        f'<rect class="frame" x="36" y="28" width="{width - 72}" height="{height - 56}" rx="24"/>',
        text(72, 82, title, "title"),
        text(76, 114, subtitle, "subtitle"),
    ]


def write_overview() -> None:
    width, height = 1500, 860
    parts = header(
        width,
        height,
        "Bluetape4k Exposed module relationships",
        "Generated from README module table and Gradle source layout.",
    )
    parts.append(rounded(570, 142, 360, 74, "#E8F3FF", "#75A9E8", 16, "group"))
    parts.append(text(750, 175, "BOM", "gtitle", "middle"))
    parts.append(text(750, 199, BOM, "label", "middle"))

    positions = {
        "foundation": (82, 270, 380, 158),
        "repositories": (562, 270, 380, 186),
        "cache": (1042, 270, 380, 250),
        "columns": (82, 548, 380, 198),
        "dialects": (562, 548, 380, 250),
        "spring": (1042, 548, 380, 186),
    }
    for group in GROUPS:
        x, y, w, h = positions[group["key"]]
        parts.append(rounded(x, y, w, h, group["color"], group["stroke"], 16, "group"))
        parts.append(text(x + w / 2, y + 34, group["title"], "gtitle", "middle"))
        parts.append(text(x + 22, y + 58, group["caption"], "small"))
        col_w = (w - 62) / 2
        for idx, module in enumerate(group["modules"]):
            col = idx % 2
            row = idx // 2
            parts.append(module_pill(x + 22 + col * (col_w + 18), y + 76 + row * 32, col_w, module))

    edges = [
        ("M750 216 L750 246 L272 246 L272 270", "arrow-blue"),
        ("M750 216 L750 270", "arrow-blue"),
        ("M750 216 L750 246 L1232 246 L1232 270", "arrow-blue"),
        ("M750 456 L750 515 L272 515 L272 548", "arrow-green"),
        ("M750 456 L750 548", "arrow-green"),
        ("M942 363 L1018 363 L1018 395 L1042 395", "arrow-green"),
        ("M942 641 L1018 641 L1018 641 L1042 641", "arrow-purple"),
        ("M462 641 L538 641 L538 641 L562 641", "arrow-blue"),
    ]
    for d, marker in edges:
        color = {"arrow-blue": "#557FAF", "arrow-green": "#4F8F6C", "arrow-purple": "#7B65B6"}[marker]
        parts.append(f'<path class="edge" d="{d}" stroke="{color}" marker-end="url(#{marker})"/>')

    parts.append(text(width / 2, 814, "All image labels are English-only and shared by README.md and README.ko.md.", "small", "middle"))
    parts.append("</svg>")
    (OUT / "root-readme-overview-01.svg").write_text("\n".join(parts) + "\n", encoding="utf-8")


def write_relationships() -> None:
    width, height = 1500, 670
    parts = header(
        width,
        height,
        "Root README module composition",
        "Each bar is sourced from the current README module table.",
    )
    max_count = max(len(group["modules"]) for group in GROUPS)
    y = 158
    for group in GROUPS:
        count = len(group["modules"])
        bar_w = 760 * count / max_count
        parts.append(text(92, y + 25, group["title"], "gtitle"))
        parts.append(text(94, y + 49, group["caption"], "small"))
        parts.append(rounded(600, y, 760, 38, "#EEF4F9", "#D7E2EC", 11))
        parts.append(rounded(600, y, bar_w, 38, group["color"], group["stroke"], 11))
        parts.append(text(600 + bar_w + 22, y + 27, str(count), "count"))
        y += 70

    total = sum(len(group["modules"]) for group in GROUPS)
    parts.append(rounded(92, 586, 360, 42, "#FFFFFF", "#D7E2EC", 12))
    parts.append(text(272, 613, f"{total} README modules + 1 BOM", "label", "middle"))
    parts.append(text(750, 614, "Relationship diagram and composition chart use the same validated module model.", "small", "middle"))
    parts.append("</svg>")
    (OUT / "root-readme-module-relationships-01.svg").write_text("\n".join(parts) + "\n", encoding="utf-8")


def main() -> None:
    validate_source()
    OUT.mkdir(parents=True, exist_ok=True)
    write_overview()
    write_relationships()


if __name__ == "__main__":
    main()
