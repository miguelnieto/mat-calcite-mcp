# mat-calcite-mcp

A [Model Context Protocol](https://modelcontextprotocol.io) server that lets an AI assistant investigate Java heap
dumps (`.hprof`): query them with SQL ([Apache Calcite](https://calcite.apache.org)), use the per-object actions of
[Eclipse Memory Analyzer](https://eclipse.dev/mat/) (MAT), and run leak and waste analyses that return data the
assistant can reason about.

It runs headless from one jar, `java -jar mat-calcite-mcp.jar`, with no MAT installation and no GUI. Inside, MAT's
heap-dump engine runs unchanged in a minimal OSGi framework (Equinox).

It's derived from the MCP server of [mat-calcite-plugin](https://github.com/vlsi/mat-calcite-plugin), which runs
inside a MAT installation instead. See [Acknowledgements](#acknowledgements).

## Requirements

Java 17 or newer, or Docker. Nothing else.

## Install

Either:
- **Jar:** download `mat-calcite-mcp-<version>.jar` from the
  [releases](https://github.com/miguelnieto/mat-calcite-mcp/releases). Each release also has the jar's SHA-256.
- **Container image:** `docker pull ghcr.io/miguelnieto/mat-calcite-mcp:<version>` (amd64 and arm64; `latest` is
  the newest release).

## Use it

Register the server with your MCP client. For Claude Code, with the jar:

    claude mcp add mat-sql -- java -Xmx4g -jar /path/to/mat-calcite-mcp-<version>.jar

or with Docker:

    claude mcp add mat-sql -- docker run -i --rm --user "$(id -u):$(id -g)" -v /path/to/dumps:/dumps ghcr.io/miguelnieto/mat-calcite-mcp:<version>

The server speaks MCP on stdin/stdout, so Docker runs it with `-i` and no TTY.

- **Heap:** MAT needs about as much heap as the dump's size. With the jar, set `-Xmx`. With Docker, the heap is
  75% of the container's memory limit (`--memory 8g`), or set `-e JAVA_TOOL_OPTIONS=-Xmx4g`.
- **Dumps in Docker:** the assistant opens them as `/dumps/<file>.hprof`. The folder must be writable, since MAT
  writes its index files there; `--user` makes them yours rather than root's.

### Tools

| Area | Tools |
|---|---|
| Dumps and schema | `open_heap_dump`, `list_heap_dumps`, `close_heap_dump`, `list_tables`, `describe_table` |
| SQL | `sql_query` (markdown, row limit), `explain_sql`, `sql_reference`, `export_query` (CSV, all rows) |
| One object, as on MAT's context menu | `object_info`, `list_references`, `retained_set`, `path_to_gc_roots`, `immediate_dominators` |
| Any other MAT command | `mat_query`, e.g. `top_consumers`, `find_leaks`, `dominator_tree` |
| Analyses | `heap_overview`, `histogram`, `leak_suspects`, `compare_dumps`, `collection_contents`, `duplicate_strings`, `wasteful_collections` |
| Control | `cancel_query`; every long call also takes `timeout_seconds` |

Results show objects as `class @ 0xaddress`, and the object tools take those addresses, so the assistant can chain
calls: find the biggest maps with `sql_query`, then ask `path_to_gc_roots` why one is alive.

### Guides for the assistant

- [`docs/querying.md`](docs/querying.md): the SQL dialect, the table functions, the Calcite features that work
  (`QUALIFY`, `ASOF JOIN`, window frames, bitwise operators) and those that don't, and investigation recipes. Every
  query in it was run against a real dump.
- [`skills/mat-heap-analysis`](skills/mat-heap-analysis/SKILL.md): the same as a short Claude Code skill. Copy the
  folder to `~/.claude/skills/` (or a project's `.claude/skills/`) and the assistant loads it when you ask about a
  heap dump.

### Files it writes

- **MAT's index files:** opening a dump writes them next to it, so a dump's directory must be writable. The first
  open of a large dump builds them, which takes a while; later opens reuse them.
- **Preferences and logs:** in `~/.mat-calcite-mcp/workspace`, or the directory given with
  `-Dosgi.instance.area=<dir>`.
- **CSV exports:** `export_query` writes only `.csv` files, and only inside the export directory. That is the dump's
  directory, unless the environment variable `MAT_MCP_EXPORT_DIR` names another one.

### Security

The assistant reads heap contents. A heap can hold text written by outsiders, such as request bodies, messages or
log lines, possibly written to steer an assistant. So:

- **File writes:** the server writes files only as described above. Text cells that a spreadsheet would run as a
  formula get a leading `'`.
- **MAT commands:** `mat_query` refuses commands that read or write files, open other dumps or run reports.
  `compare_dumps` compares dumps that are already open.
- **Runaway work:** long calls stop at their timeout or on `cancel_query`. That includes SQL scans of large
  classes, which check for cancellation while they run.

## Build

    ./gradlew check :server:export

This writes `server/build/distributions/executable/mat-calcite-mcp.jar` and runs the tests inside Equinox with MAT.
Any JDK 17 or newer builds it; the bytecode is Java 17.

### How it's put together

- **MAT and the Eclipse runtime** aren't on Maven Central. Gradle reads their p2 update sites as Ivy repositories:
  a site's `plugins/` folder holds plain `<bundle>_<version>.jar` files (see `build.gradle.kts`). Pinned in
  `gradle/mat-platform.gradle.kts` and `gradle/eclipse-platform.gradle.kts`:
  - MAT 1.17.0's parser, hprof and report bundles.
  - The Eclipse 4.40 runtime bundles MAT 1.17.0 ships with, at the same versions.
  - Felix SCR, which runs the Eclipse runtime's Declarative Services components.
- **MAT's api bundle is rebuilt for Java 17** (`mat-api-java17/`). MAT 1.17.0 compiled that one bundle for Java 21;
  its only Java 21 trace is a bridge method the compiler adds for `List.reversed()`. The build:
  1. downloads MAT's source at the commit 1.17.0 was built from, and checks a digest of the files;
  2. compiles it with `--release 17`;
  3. packs the classes into the released jar's other content, under the version `1.17.0.202606011933-java17`
     and a manifest that asks for Java 17.

  Apart from compiler conventions and that bridge method, the rebuilt classes have the same fields and methods
  as the released ones. The rebuilt jar isn't signed.
- **Calcite and the libraries it uses** are embedded in the server bundle (`Bundle-ClassPath`), not wrapped one by
  one: see `server/build.gradle.kts`.
- **[bnd](https://bnd.bndtools.org)** builds the bundles, resolves `server/mat-calcite-mcp.bndrun` and exports the
  runnable jar.
- **Every downloaded artifact is checksum-verified** (`gradle/verification-metadata.xml`). The checksums of the
  MAT and Eclipse jars match the SHA-256 their p2 sites publish; MAT's api sources are checked by their own digest.

To change a dependency, update its version, then regenerate the checksums with
`./gradlew --write-verification-metadata sha256 check :server:export`. Review the new entries before committing.

## Acknowledgements

Much of the work, and the idea, come from [mat-calcite-plugin](https://github.com/vlsi/mat-calcite-plugin) by
Vladimir Sitnikov: the Calcite schema that turns a heap dump into SQL tables, the heap functions and the table
functions are its. That project is an Eclipse Memory Analyzer plugin with a user interface (a SQL editor, a schema
browser, content assist), and it is the one to use when you want to explore a dump by hand inside MAT.

This project keeps only the headless part, as a standalone server for AI assistants, with no UI and no MAT
installation. That keeps it small and simple to run, and it may suit unattended runs such as a CI pipeline better,
where there is no desktop to open MAT on.

## License

Apache License 2.0; see `LICENSE`. The runnable jar bundles third-party software under its own licenses (EPL-2.0
for MAT and Eclipse, among others); see `NOTICE`.
