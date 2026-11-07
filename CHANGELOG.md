# Changelog

## [Unreleased]

- Documentation for assistants: `docs/querying.md` (SQL dialect, table functions, recipes, what doesn't work, and the
  MAT commands that work through `mat_query`) and a `mat-heap-analysis` Claude Code skill in `skills/`.

## [0.1.0] - 2026-11-07

First release.

- An MCP server for Java heap dumps that runs from one jar (`java -jar`) or a container image, with no Eclipse
  Memory Analyzer installation. MAT 1.17.0's heap-dump engine runs unchanged inside a minimal Equinox.
- 23 tools:
  - SQL over the heap with Apache Calcite 1.42 (`sql_query`, `explain_sql`, `export_query` to CSV);
  - MAT's per-object actions (`object_info`, `list_references`, `retained_set`, `path_to_gc_roots`,
    `immediate_dominators`), plus `mat_query` for any other MAT command;
  - analyses (`heap_overview`, `histogram`, `leak_suspects`, `compare_dumps`, `collection_contents`,
    `duplicate_strings`, `wasteful_collections`).
- Runs on Java 17 and newer: MAT 1.17.0's api bundle, the only one built for Java 21, is compiled for Java 17
  from its released source.
- Security: no file writes besides MAT's index files and CSV exports inside the export directory; `mat_query`
  refuses MAT commands that read or write files; long calls stop on timeout or `cancel_query`.
- Bundled libraries are at versions without known vulnerabilities (Avatica 1.29.0, httpclient5 5.6.5,
  jackson-core 2.22.4, Guava 33.8.0, commons-lang3 3.21.0, json-smart 2.6.1), and every downloaded artifact
  is checksum-verified.
- Derived from the MCP server of [mat-calcite-plugin](https://github.com/vlsi/mat-calcite-plugin).
