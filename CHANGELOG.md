# Changelog

## [Unreleased]

- Documentation for assistants: `docs/querying.md` (SQL dialect, table functions, recipes, what doesn't work, and the
  MAT commands that work through `mat_query`) and a `mat-heap-analysis` Claude Code skill in `skills/`.
- New tool `dominator_tree`: MAT's dominator tree for the whole heap, as objects or summed by class, class loader or
  package (`group_by`), or what one object dominates (`address`).
- New tool `threads`: the threads of the dumped JVM with state and retained heap, optionally with stack frames and the
  heap their locals hold, or the details of given threads.
- `leak_suspects` goes beyond MAT's Leak Suspects report and can return its numbers as data. Each suspect now also
  has what its retained heap is made of by class, what it dominates, its path from a GC root with the reference at
  each step, and, for a collection at the accumulation point, its size, capacity and fill ratio with a few entries.
  `format` json returns one JSON document of raw numbers (bytes, counts, percentages, addresses) that the text is
  rendered from.
- **Fixed:** `wasteful_collections` counted the slots of an empty collection twice, once in what it retains and once as
  unused slots, so an empty `new ArrayList<>(100)` showed 840 bytes of waste instead of 440. The "unused slots" column
  now covers only the collections that are not empty, and the single-collection list says how many slots an empty
  one has allocated.
- **Changed:** the container image no longer runs as root. It runs as uid 1000 (gid 1000), the first user of most Linux
  systems, and `--user` still overrides it. A dumps folder that you own with uid 1000 is writable out of the box, and
  the index files MAT writes there are yours. If the folder is not writable for uid 1000, pass `--user` with your own
  ids, or make the folder writable; before, root could write to any folder.
- The Docker examples run with `--network none`, as a JSON client configuration does too, and the README has a "Run it
  locked down" section with a read-only filesystem, no capabilities and resource limits, tested together with the
  tools. The server needs no network. The release workflow now smoke-tests the published image locked down, so a
  change that makes the server need the network, or a writable filesystem, fails the release.
- New memory inspections: `memory_inspections` ranks what wastes memory (duplicate strings, empty and under-filled
  collections, zero-length, sparse and constant arrays, fields that are null in most objects) and what may leak (stale
  thread-local values, classes defined by several class loaders), each with the tool that lists the examples.
  `thread_locals`, `array_waste` and `null_fields` are the new details. Estimates are upper bounds in bytes and can
  overlap. Their tests build a heap with a known shape in a child JVM when they start, so no binary dump is added.
- New tools with explicit options for MAT analyses that were only reachable through `mat_query`:
  `merge_paths_to_gc_roots`, `gc_roots`, `class_references`, `big_drops`, `unreachable_objects`,
  `reference_statistics`, `collection_stats`, `group_by_value`, `find_strings`, `duplicate_classes`,
  `system_properties` and `oql`. The values that go into a MAT command line are validated, so a space, a quote or a
  leading dash cannot add an option such as a file to write.
- `histogram` takes `group_by` (class, package, superclass, class_loader); `path_to_gc_roots` takes
  `references`, the choices of MAT's Path To GC Roots menu (all, exclude_weak, exclude_soft, exclude_phantom,
  exclude_weak_soft, exclude_phantom_soft, exclude_phantom_weak, exclude_all), and says when only references that
  are left out reach an object;
  `retained_set` takes `excluded_references` (MAT's customized retained set, without opening its file option to
  `mat_query`).
- **Changed:** `list_references` now defaults to `direction` outgoing, as in MAT (List objects > with outgoing
  references), where 0.1.0 defaulted to incoming. Pass `direction` incoming for the referrers. `class_references`
  follows the same default, and a `direction` other than outgoing or incoming is now an error.
- SQL: table function `getDominatedObjects(x)`, and `isGcRoot(x)` and `getGcRootTypes(x)`.
- `mat_query` now shows MAT's report results as text instead of a placeholder: `thread_details`, `leakhunter`,
  `finalizer_overview` and the weak, soft, phantom and finalizer reference statistics. Charts are left out, HTML is
  turned into text, and text results are cut at `max_rows` lines.

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
