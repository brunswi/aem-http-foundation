#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generate docs pages from the repo source Markdown files for MkDocs.

Source Markdown keeps relative links (good for GitHub and local editing).
Generated pages rewrite most repo-relative links to GitHub blob URLs; cross-links
between top-level docs use MkDocs sibling pages (`index.md` / `reference.md` /
`integration.md` / `examples.md`) so the site stays in-docs.

Resolve blob base (first match):
  1. DOCS_REPO_BLOB_BASE — full prefix including branch, e.g. https://github.com/owner/repo/blob/develop
  2. GITHUB_REPOSITORY + branch (see repo_branch())
  3. git remote origin + branch

Branch for /blob/<branch>/... (ignored if DOCS_REPO_BLOB_BASE is set), first match:
  - DOCS_REPO_BRANCH — e.g. develop or main
  - GITHUB_REF_NAME — set in GitHub Actions to the triggering branch
  - git rev-parse --abbrev-ref HEAD — current local branch
  - main — fallback if not in a git repo or detached HEAD

Usage: python docs/scripts/generate_docs_pages.py
  DOCS_REPO_BRANCH=develop python docs/scripts/generate_docs_pages.py
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
_SCRIPT = "docs/scripts/generate_docs_pages.py"


def repo_branch() -> str:
    explicit = os.environ.get("DOCS_REPO_BRANCH", "").strip()
    if explicit:
        return explicit
    ref = os.environ.get("GITHUB_REF_NAME", "").strip()
    if ref:
        return ref
    try:
        head = subprocess.check_output(
            ["git", "-C", str(REPO_ROOT), "rev-parse", "--abbrev-ref", "HEAD"],
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        head = ""
    if head and head != "HEAD":
        return head
    return "main"


def blob_base() -> str:
    explicit = os.environ.get("DOCS_REPO_BLOB_BASE", "").strip().rstrip("/")
    if explicit:
        return explicit
    branch = repo_branch()
    gh = os.environ.get("GITHUB_REPOSITORY", "").strip()
    if gh:
        return f"https://github.com/{gh}/blob/{branch}"
    try:
        url = subprocess.check_output(
            ["git", "-C", str(REPO_ROOT), "remote", "get-url", "origin"],
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        url = ""
    m = re.search(r"github\.com[:/]([^/]+)/([^/.]+)", url)
    if m:
        return f"https://github.com/{m.group(1)}/{m.group(2)}/blob/{branch}"
    print(
        "Could not resolve GitHub blob base. Set DOCS_REPO_BLOB_BASE "
        "or GITHUB_REPOSITORY, or add a github.com remote named origin.",
        file=sys.stderr,
    )
    sys.exit(1)


def rewrite_links(text: str, link_map: dict[str, str]) -> str:
    """Rewrite Markdown link targets using link_map, including #fragment variants."""
    if not link_map:
        return text
    pattern = re.compile(
        r"\]\((" + "|".join(re.escape(k) for k in link_map) + r")(#[^)]+)?\)"
    )
    return pattern.sub(
        lambda m: f"]({link_map[m.group(1)]}{m.group(2) or ''})",
        text,
    )


@dataclass
class PageSpec:
    source: str                         # path relative to REPO_ROOT
    dest: str                           # path relative to REPO_ROOT
    edit_hint: str                      # shown in the generated banner
    links: dict[str, str] = field(default_factory=dict)  # regex-based link rewrites
    raw: list[tuple[str, str]] = field(default_factory=list)  # prefix replacements (blob URLs)


def page_specs(base: str) -> list[PageSpec]:
    return [
        PageSpec(
            source="README.md",
            dest="docs/index.md",
            edit_hint="README.md",
            links={
                "./core/REFERENCE.md": "reference.md",
                "core/REFERENCE.md": "reference.md",
                "INTEGRATION.md": "integration.md",
                "EXAMPLES.md": "examples.md",
                "LICENSE": f"{base}/LICENSE",
            },
            # Runs after links, so core/REFERENCE.md is already gone.
            raw=[("](core/", f"]({base}/core/)")],
        ),
        PageSpec(
            source="core/REFERENCE.md",
            dest="docs/reference.md",
            edit_hint="core/REFERENCE.md",
            links={
                "../README.md": "index.md",
                "REFERENCE.md": "reference.md",
                "../EXAMPLES.md": "examples.md",
                "../INTEGRATION.md": "integration.md",
            },
            raw=[("](src/", f"]({base}/core/src/)")],
        ),
        PageSpec(
            source="INTEGRATION.md",
            dest="docs/integration.md",
            edit_hint="INTEGRATION.md",
            links={
                "EXAMPLES.md": "examples.md",
                "core/REFERENCE.md": "reference.md",
                "README.md": "index.md",
            },
        ),
        PageSpec(
            source="EXAMPLES.md",
            dest="docs/examples.md",
            edit_hint="EXAMPLES.md",
            links={
                "core/REFERENCE.md": "reference.md",
                "INTEGRATION.md": "integration.md",
                "README.md": "index.md",
            },
        ),
    ]


def generate_page(spec: PageSpec) -> Path:
    source = REPO_ROOT / spec.source
    dest = REPO_ROOT / spec.dest
    banner = f"<!-- Generated by {_SCRIPT} — edit {spec.edit_hint}, not this file. -->\n\n"
    text = source.read_text(encoding="utf-8")
    if text.startswith(banner):
        text = text[len(banner):]
    text = rewrite_links(text, spec.links)
    for old, new in spec.raw:
        text = text.replace(old, new)
    dest.write_text(banner + text, encoding="utf-8")
    return dest


def main() -> None:
    base = blob_base()
    written = [generate_page(spec) for spec in page_specs(base)]
    print("Wrote " + ", ".join(str(p.relative_to(REPO_ROOT)) for p in written))
    print(f"Blob base: {base}")
    if not os.environ.get("DOCS_REPO_BLOB_BASE", "").strip():
        print(f"Branch: {repo_branch()}")


if __name__ == "__main__":
    main()
