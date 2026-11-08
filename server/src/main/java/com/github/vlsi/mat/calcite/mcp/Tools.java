package com.github.vlsi.mat.calcite.mcp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Names, descriptions and input schemas of the tools of {@link McpServer}.
 */
final class Tools {
  static final String OPEN = "open_heap_dump";
  static final String LIST_DUMPS = "list_heap_dumps";
  static final String LIST_TABLES = "list_tables";
  static final String DESCRIBE = "describe_table";
  static final String QUERY = "sql_query";
  static final String EXPLAIN = "explain_sql";
  static final String REFERENCE = "sql_reference";
  static final String CLOSE = "close_heap_dump";
  static final String OBJECT_INFO = "object_info";
  static final String REFERENCES = "list_references";
  static final String RETAINED_SET = "retained_set";
  static final String PATH_TO_GC_ROOTS = "path_to_gc_roots";
  static final String IMMEDIATE_DOMINATORS = "immediate_dominators";
  static final String DOMINATOR_TREE = "dominator_tree";
  static final String THREADS = "threads";
  static final String MERGE_PATHS = "merge_paths_to_gc_roots";
  static final String GC_ROOTS = "gc_roots";
  static final String CLASS_REFERENCES = "class_references";
  static final String BIG_DROPS = "big_drops";
  static final String UNREACHABLE = "unreachable_objects";
  static final String REFERENCE_STATISTICS = "reference_statistics";
  static final String COLLECTION_STATS = "collection_stats";
  static final String GROUP_BY_VALUE = "group_by_value";
  static final String FIND_STRINGS = "find_strings";
  static final String DUPLICATE_CLASSES = "duplicate_classes";
  static final String SYSTEM_PROPERTIES = "system_properties";
  static final String OQL = "oql";
  static final String MAT_QUERY = "mat_query";
  static final String CANCEL = "cancel_query";
  static final String HEAP_OVERVIEW = "heap_overview";
  static final String LEAK_SUSPECTS = "leak_suspects";
  static final String COMPARE_DUMPS = "compare_dumps";
  static final String COLLECTION_CONTENTS = "collection_contents";
  static final String EXPORT_QUERY = "export_query";
  static final String DUPLICATE_STRINGS = "duplicate_strings";
  static final String WASTEFUL_COLLECTIONS = "wasteful_collections";
  static final String HISTOGRAM = "histogram";

  static final List<String> NAMES = Arrays.asList(OPEN, LIST_DUMPS, LIST_TABLES, DESCRIBE, QUERY, EXPLAIN, REFERENCE,
      CLOSE, OBJECT_INFO, REFERENCES, RETAINED_SET, PATH_TO_GC_ROOTS, IMMEDIATE_DOMINATORS, DOMINATOR_TREE, THREADS,
      MERGE_PATHS, GC_ROOTS, CLASS_REFERENCES, BIG_DROPS, UNREACHABLE, REFERENCE_STATISTICS, COLLECTION_STATS,
      GROUP_BY_VALUE, FIND_STRINGS, DUPLICATE_CLASSES, SYSTEM_PROPERTIES, OQL, MAT_QUERY, CANCEL, HEAP_OVERVIEW, HISTOGRAM, LEAK_SUSPECTS, COMPARE_DUMPS, COLLECTION_CONTENTS, EXPORT_QUERY, DUPLICATE_STRINGS,
      WASTEFUL_COLLECTIONS);
  /** Tools that can run long, and so take timeout_seconds. */
  static final List<String> TIMED = Arrays.asList(OPEN, DESCRIBE, QUERY, EXPLAIN, OBJECT_INFO, REFERENCES, RETAINED_SET,
      PATH_TO_GC_ROOTS, IMMEDIATE_DOMINATORS, DOMINATOR_TREE, THREADS, MERGE_PATHS, GC_ROOTS,
      CLASS_REFERENCES, BIG_DROPS, UNREACHABLE, REFERENCE_STATISTICS, COLLECTION_STATS, GROUP_BY_VALUE, FIND_STRINGS,
      DUPLICATE_CLASSES, SYSTEM_PROPERTIES, OQL, MAT_QUERY, HEAP_OVERVIEW, LEAK_SUSPECTS, COMPARE_DUMPS,
      COLLECTION_CONTENTS, EXPORT_QUERY, DUPLICATE_STRINGS, WASTEFUL_COLLECTIONS, HISTOGRAM);

  /** MAT's Path To GC Roots menu, as the references option of the two path tools. */
  private static final Map.Entry<String, Object> REFERENCES_OPTION = prop("references", "string", "Which references to leave "
      + "out of the paths, as in MAT's menu: all (with all references), exclude_weak, exclude_soft, exclude_phantom, "
      + "exclude_weak_soft, exclude_phantom_soft, exclude_phantom_weak, or exclude_all (default: exclude all "
      + "phantom/weak/soft etc. references)");

  private static final String ADDRESS_ONE = "Object address as shown in results, e.g. 0x7bfeb5848";
  private static final String ADDRESSES = "One or more object addresses, separated by spaces or commas, e.g. "
      + "0x7bfeb5848";
  private static final String DUMP_DESCRIPTION = "Path or file name of an open heap dump; optional while only one is "
      + "open";

  private Tools() {
  }

  static List<Object> definitions() {
    List<Object> tools = new ArrayList<>();
    tools.add(tool(OPEN, "Open a Java heap dump (.hprof) for querying. The first open of a dump builds MAT index "
            + "files next to it, which can take minutes for large dumps; later opens are fast. Returns object and "
            + "class counts. cancel_query stops an open that takes too long.",
        properties(prop("path", "string", "Absolute path of the heap dump file"),
            prop("timeout_seconds", "integer", "Stop opening after this many seconds (default: no limit, since "
                + "building the indexes of a large dump can take long)")), "path"));
    tools.add(tool(LIST_DUMPS, "List the heap dumps that are open.", properties()));
    tools.add(tool(LIST_TABLES, "Find tables: every Java class is a table, named as it must be written in SQL. "
            + "Filter by part of the class name, e.g. \"HashMap\" or \"com.example\".",
        properties(prop("dump", "string", DUMP_DESCRIPTION),
            prop("filter", "string", "Part of the class name, ignoring case; empty lists all tables"),
            prop("limit", "integer", "Maximum number of tables (default 200)"))));
    tools.add(tool(DESCRIBE, "Columns and SQL types of a table, e.g. \"java.util.HashMap\" or "
            + "instanceof.\"java.util.AbstractMap\".",
        properties(prop("dump", "string", DUMP_DESCRIPTION),
            prop("table", "string", "Table as written in SQL, e.g. \"java.lang.String\""), timeout()), "table"));
    tools.add(tool(QUERY, "Run a SQL query (Apache Calcite dialect) against the heap and return the rows as a "
            + "markdown table. Objects are shown as text @ address. Call sql_reference first for the heap tables, "
            + "columns and functions. Prefer aggregates (count, sum(retainedSize(this))) and order by + limit over "
            + "listing many objects.",
        properties(prop("dump", "string", DUMP_DESCRIPTION),
            prop("sql", "string", "The query, e.g. select toString(this), retainedSize(this) r from "
                + "\"java.lang.String\" order by r desc limit 10"),
            prop("max_rows", "integer", "Maximum rows to return (default 100, at most 5000)"), timeout()), "sql"));
    tools.add(tool(EXPLAIN, "Show Calcite's execution plan for a query without running it.",
        properties(prop("dump", "string", DUMP_DESCRIPTION), prop("sql", "string", "The query"), timeout()), "sql"));
    tools.add(tool(REFERENCE, "Reference of the SQL dialect for heap dumps: table naming, virtual columns "
            + "(this['@retained']...), heap functions (retainedSize, getField, getByKey, toString...) and table "
            + "functions (getRetainedSet, getOutboundReferences...). Read it before writing queries.",
        properties()));
    tools.add(tool(CLOSE, "Close an open heap dump and free its memory.",
        properties(prop("dump", "string", DUMP_DESCRIPTION))));
    tools.add(tool(OBJECT_INFO, "Details of one object: class, shallow and retained size, GC root type, immediate "
            + "dominator, and its field values (static fields for a class, elements for an array).",
        properties(prop("dump", "string", DUMP_DESCRIPTION), prop("address", "string", ADDRESS_ONE),
            timeout()), "address"));
    tools.add(tool(REFERENCES, "Objects that the given objects reference (direction outgoing, the default) or that "
            + "reference them (direction incoming: who keeps them alive, their parents). Like MAT's List objects > with "
            + "outgoing / incoming references.",
        properties(prop("dump", "string", DUMP_DESCRIPTION), prop("address", "string", ADDRESSES),
            prop("direction", "string", "outgoing (default) or incoming"),
            prop("depth", "integer", "Levels of references to follow (default 1)"), maxRows(), maxChildren(),
            timeout()), "address"));
    tools.add(tool(RETAINED_SET, "What the given objects keep alive: the objects that would be freed with them, as "
            + "a histogram by class with counts and sizes. With excluded_references, references of those kinds from "
            + "other objects do not count as keeping anything alive, so objects that are also held through them, "
            + "e.g. by weak caches (java.lang.ref.Reference:referent), count as retained here.",
        properties(prop("dump", "string", DUMP_DESCRIPTION), prop("address", "string", ADDRESSES),
            prop("excluded_references", "string", "References to ignore, as class:field separated by spaces or "
                + "commas (class names include subclasses), e.g. java.lang.ref.Reference:referent (default: none)"),
            maxRows(),
            timeout()), "address"));
    tools.add(tool(PATH_TO_GC_ROOTS, "Why an object is alive: the shortest reference paths from GC roots to it, as a "
            + "tree. Like MAT's Path To GC Roots menu, the references option decides which references are left out; "
            + "by default weak, soft, phantom and finalizer references, so an object that only they reach shows no "
            + "path. The key tool for memory leaks.",
        properties(prop("dump", "string", DUMP_DESCRIPTION), prop("address", "string", ADDRESS_ONE),
            prop("paths", "integer", "Number of paths (default 3)"),
            REFERENCES_OPTION,
            prop("depth", "integer", "Tree levels to show (default 30)"), maxRows(), maxChildren(), timeout()),
        "address"));
    tools.add(tool(IMMEDIATE_DOMINATORS, "The objects that directly dominate (keep alive) the given objects, grouped "
            + "by class. <ROOT> means an object is reachable from several GC roots, so no single object keeps it.",
        properties(prop("dump", "string", DUMP_DESCRIPTION), prop("address", "string", ADDRESSES),
            prop("skip", "string", "Regular expression of dominator classes to step over, e.g. java\\..*|sun\\..* to "
                + "find the first application-level dominator. Default: none (the true immediate dominators)."),
            maxRows(), timeout()), "address"));
    tools.add(tool(DOMINATOR_TREE, "MAT's dominator tree: the objects that keep the most memory alive, with shallow "
            + "and retained heap and the share of the heap. Without address it covers the whole heap: as single "
            + "objects (group_by none, the default), or summed by class, class loader or package. With address it shows "
            + "what that one object dominates, level by level.",
        properties(prop("dump", "string", DUMP_DESCRIPTION),
            prop("address", "string", "Show what this object dominates, e.g. 0x7bfeb5848 (cannot be combined with "
                + "group_by)"),
            prop("group_by", "string", "none (default), class, class_loader or package; the whole heap only"),
            prop("depth", "integer", "Tree levels below the top elements to show (default 1)"), maxRows(),
            maxChildren(), timeout())));
    tools.add(tool(THREADS, "Threads of the dumped JVM: name, state, daemon flag, priority, context class loader and "
            + "the heap each retains, largest first. Memory held by a thread's local variables is the first thing to "
            + "check when a thread (a request, a pool worker) retains much. With address it gives the details of "
            + "those threads: properties and stack frames.",
        properties(prop("dump", "string", DUMP_DESCRIPTION),
            prop("address", "string", "Thread objects to describe, e.g. 0x7bfeb5848; empty lists all threads"),
            prop("include_stacks", "boolean", "Also list each thread's stack frames with the heap its locals retain "
                + "(default false; all threads only)"),
            prop("max_rows", "integer", "Threads to list, or lines in total (default 100)"),
            prop("max_frames", "integer", "Stack frames shown per thread (default 20)"), timeout())));
    tools.add(tool(MERGE_PATHS, "Why many objects are alive: the shortest paths from GC roots to all of them, merged "
            + "into one tree, so shared parents show once. Give either addresses or a class regex (all instances).",
        properties(prop("dump", "string", DUMP_DESCRIPTION), prop("address", "string", ADDRESSES + " (or use "
                + "class_regex)"),
            prop("class_regex", "string", "Regular expression for the whole class name, e.g. java\\.util\\.Properties "
                + "or com\\.example\\..*; no spaces (or use address)"),
            prop("group_by", "string", "from_gc_roots (default: the tree starts at the GC roots), "
                + "from_gc_roots_by_class (the roots' classes first) or from_objects_by_class (the objects' classes "
                + "first)"),
            REFERENCES_OPTION,
            prop("depth", "integer", "Tree levels to show (default 30)"), maxRows(), maxChildren(), timeout())));
    tools.add(tool(GC_ROOTS, "The GC roots of the heap by type (system class, JNI global, thread block, Java local...) "
            + "with the classes of the objects under each.",
        properties(prop("dump", "string", DUMP_DESCRIPTION),
            prop("depth", "integer", "Tree levels to show (default 1)"), maxRows(), maxChildren(), timeout())));
    tools.add(tool(CLASS_REFERENCES, "What the instances of the matching classes reference (outgoing, the default) or "
            + "who references them (incoming), summed up by class: with incoming, for example which classes hold all "
            + "the URLs. For single objects use list_references.",
        properties(prop("dump", "string", DUMP_DESCRIPTION),
            prop("class_regex", "string", "Regular expression for the whole class name, e.g. java\\.net\\.URL; no "
                + "spaces"),
            prop("direction", "string", "outgoing (default) or incoming"),
            prop("depth", "integer", "Tree levels to show (default 1)"), maxRows(), maxChildren(), timeout()),
        "class_regex"));
    tools.add(tool(BIG_DROPS, "Where the memory piles up: objects whose retained heap is much larger than the sum "
            + "of their children's, in MAT's dominator tree (accumulation points of a leak), with the dominator of "
            + "each. A complement to leak_suspects.",
        properties(prop("dump", "string", DUMP_DESCRIPTION),
            prop("skip", "string", "Regular expression of dominator classes to step over (default: java.*, javax.*, "
                + "com.sun.*, sun.* and jdk.*); none steps over nothing"),
            prop("depth", "integer", "Tree levels to show (default 1)"), maxRows(), maxChildren(), timeout())));
    tools.add(tool(UNREACHABLE, "Objects that no GC root reaches (garbage still in the dump), per class with counts "
            + "and sizes. A dump taken without a full GC contains such objects; MAT keeps them apart from the heap.",
        properties(prop("dump", "string", DUMP_DESCRIPTION), maxRows(), timeout())));
    tools.add(tool(REFERENCE_STATISTICS, "What weak, soft, phantom or finalizer references hold: the referents by "
            + "class, those kept alive only by the references, and those that are also strongly held. Soft or weak "
            + "caches that retain much memory show here.",
        properties(prop("dump", "string", DUMP_DESCRIPTION),
            prop("kind", "string", "weak (default), soft, phantom, finalizer or custom"),
            prop("class_regex", "string", "For kind custom: a regular expression for the whole name of a Reference "
                + "subclass, e.g. com\\.example\\.CacheRef"), maxRows(), timeout())));
    tools.add(tool(COLLECTION_STATS, "Distribution of a collection class: by fill ratio, by hash collision ratio, by "
            + "size, or the same for arrays, with the number of objects and the memory wasted in each bucket. For the "
            + "worst single collections use wasteful_collections.",
        properties(prop("dump", "string", DUMP_DESCRIPTION),
            prop("class_regex", "string", "Regular expression for the whole class name, e.g. java\\.util\\.HashMap "
                + "or java\\.lang\\.Object\\[\\]; no spaces"),
            prop("kind", "string", "fill_ratio, collision_ratio (maps), array_fill_ratio, collection_sizes or "
                + "array_sizes"),
            prop("segments", "integer", "Number of buckets for the ratios (default 5; not for the size kinds)"),
            maxRows(), timeout()), "class_regex", "kind"));
    tools.add(tool(GROUP_BY_VALUE, "Objects of the matching classes grouped by their value: the text of a String, "
            + "the number in an Integer, or the value of a named field. Finds duplicated values in any class, not "
            + "only strings (duplicate_strings does those).",
        properties(prop("dump", "string", DUMP_DESCRIPTION),
            prop("class_regex", "string", "Regular expression for the whole class name, e.g. java\\.lang\\.Integer; "
                + "no spaces"),
            prop("field", "string", "Group by this field's value instead, e.g. size"), maxRows(), timeout()),
        "class_regex"));
    tools.add(tool(FIND_STRINGS, "Strings whose whole text matches a regular expression, with their addresses, for "
            + "example secrets or URLs held in memory. For counting, or for part of a text, sql_query with like is "
            + "often easier.",
        properties(prop("dump", "string", DUMP_DESCRIPTION),
            prop("pattern", "string", "Regular expression the whole text must match, e.g. .*password.* (no spaces: "
                + "write \\s)"),
            prop("class_regex", "string", "Classes to search (default java.lang.String)"),
            prop("depth", "integer", "Tree levels to show (default 0: only the strings)"), maxRows(), maxChildren(),
            timeout()), "pattern"));
    tools.add(tool(DUPLICATE_CLASSES, "Classes that are defined more than once, by different class loaders, with the "
            + "loaders and the number of instances: a typical sign of a class loader leak in application servers.",
        properties(prop("dump", "string", DUMP_DESCRIPTION), maxRows(), timeout())));
    tools.add(tool(SYSTEM_PROPERTIES, "The System properties of the dumped JVM: java.version, java.vm.*, user.dir, "
            + "the command line and so on.",
        properties(prop("dump", "string", DUMP_DESCRIPTION),
            prop("filter", "string", "Only properties whose name or value contains this text, ignoring case"),
            timeout())));
    tools.add(tool(OQL, "Run an OQL query, Eclipse MAT's SQL-like language for heap objects, e.g. select s from "
            + "java.lang.String s where s.value.@length > 1000. Strings use double quotes. sql_query is usually more "
            + "powerful; use this for MAT-specific syntax (@retainedHeapSize, @length, instanceof).",
        properties(prop("dump", "string", DUMP_DESCRIPTION), prop("query", "string", "The OQL query"),
            prop("depth", "integer", "Tree levels to show (default 0)"), maxRows(), maxChildren(), timeout()),
        "query"));
    tools.add(tool(MAT_QUERY, "Run any Eclipse MAT query by its command line, e.g. \"histogram\", \"find_leaks\", "
            + "\"top_consumers\", \"dominator_tree\", \"thread_overview\", \"duplicate_classes\", "
            + "\"collection_fill_ratio\", \"gc_roots\", \"path2gc 0x...\", \"list_objects -inbound 0x...\". Objects in "
            + "command lines are hex addresses (0x...; get them in SQL with getHexAddress(this)) or, for commands that "
            + "take objects, a regular expression for class names such as java.util.Hash.*. Tables come back as "
            + "markdown, trees as indented lines, reports as text with headings. Commands that read or write files, open other dumps or run "
            + "reports (export_hprof, delta_histogram -baseline, create_report...) are refused; use compare_dumps to "
            + "compare dumps.",
        properties(prop("dump", "string", DUMP_DESCRIPTION), prop("command", "string", "The MAT command line"),
            prop("depth", "integer", "Tree levels to show (default 1)"), maxRows(), maxChildren(), timeout()),
        "command"));
    tools.add(tool(HEAP_OVERVIEW, "Start here: heap size and JVM details, the biggest objects (top of the dominator "
            + "tree) and the classes using the most memory, with addresses for the object tools.",
        properties(prop("dump", "string", DUMP_DESCRIPTION),
            prop("top", "integer", "Objects and classes to list (default 10)"), timeout())));
    tools.add(tool(HISTOGRAM, "Class histogram: objects, shallow heap and optionally retained heap per class, "
            + "filtered by class name and sorted by objects, shallow or retained heap. Retained sizes are exact for up "
            + "to 20 classes, MAT's quick lower bound above that.",
        properties(prop("dump", "string", DUMP_DESCRIPTION),
            prop("filter", "string", "Regular expression found in the class name, e.g. ^com\\.example\\. or HashMap; "
                + "empty for all classes"),
            prop("sort_by", "string", "objects, shallow (default) or retained"),
            prop("retained", "boolean", "Also compute retained heap when not sorting by it (default false)"),
            prop("group_by", "string", "class (default), package, superclass or class_loader: one row per group "
                + "instead of per class (no retained heap)"),
            prop("max_rows", "integer", "Classes or groups to list (default 30)"), timeout())));
    tools.add(tool(LEAK_SUSPECTS, "MAT's leak suspects: objects or groups of objects that retain a large share of "
            + "the heap, each with its class loader, its accumulation point (where the memory piles up) and its "
            + "path from a GC root; for a group without one, its biggest instances; for a thread, its stack frames "
            + "with the local variables that hold memory.",
        properties(prop("dump", "string", DUMP_DESCRIPTION),
            prop("threshold_percent", "integer", "Minimum share of the heap a suspect retains (default 10)"),
            prop("max_suspects", "integer", "Suspects to describe (default 5)"), timeout())));
    tools.add(tool(COMPARE_DUMPS, "Compare two open heap dumps of the same application: totals, and the classes whose "
            + "shallow heap grew the most from the baseline to the current dump (swap the two to see what shrank). "
            + "Growth between dumps is the strongest sign of a leak.",
        properties(prop("dump", "string", "The current (later) heap dump"),
            prop("baseline", "string", "The baseline (earlier) heap dump, as opened; both must be open; the path must "
                + "not contain spaces"),
            prop("max_rows", "integer", "Classes to list (default 30)"), timeout()), "baseline"));
    tools.add(tool(COLLECTION_CONTENTS, "The contents of a collection: map entries (key => value), or the elements "
            + "of a list, set or queue, with its size, capacity, fill and collision ratio. Works for the JDK "
            + "collections and others MAT knows. Pages with offset and limit.",
        properties(prop("dump", "string", DUMP_DESCRIPTION), prop("address", "string", ADDRESS_ONE),
            prop("offset", "integer", "First entry to show (default 0)"),
            prop("limit", "integer", "Entries to show (default 50)"), timeout()), "address"));
    tools.add(tool(EXPORT_QUERY, "Run a SQL query and write every row to a CSV file, with no row limit. Use it for "
            + "results too big to return; sql_query is better for reading results. Files are written only inside the "
            + "export directory: the dump's directory, or MAT_MCP_EXPORT_DIR when the user set it.",
        properties(prop("dump", "string", DUMP_DESCRIPTION), prop("sql", "string", "The query"),
            prop("path", "string", "File name ending in .csv, e.g. strings.csv, written to the export directory "
                + "(an absolute path must be inside it)"),
            prop("overwrite", "boolean", "Replace an existing file (default false)"), timeout()), "sql", "path"));
    tools.add(tool(DUPLICATE_STRINGS, "Strings stored many times with the same value: how much memory the extra "
            + "copies waste, and the worst values with an example address each.",
        properties(prop("dump", "string", DUMP_DESCRIPTION),
            prop("min_copies", "integer", "Copies a value needs to count as duplicated (default 2)"),
            prop("max_rows", "integer", "Values to list (default 30)"), timeout())));
    tools.add(tool(WASTEFUL_COLLECTIONS, "Collections that waste memory: empty ones, ones with much unused capacity, "
            + "and maps with many hash collisions, per class and the worst single ones.",
        properties(prop("dump", "string", DUMP_DESCRIPTION),
            prop("max_rows", "integer", "Collection classes to list (default 20)"), timeout())));
    tools.add(tool(CANCEL, "Cancel the tool call that is running, e.g. a slow query. Calls also stop by themselves "
        + "after timeout_seconds.", properties()));
    return tools;
  }

  private static Map<String, Object> tool(String name, String description, Map<String, Object> properties,
                                          String... required) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    if (required.length > 0) {
      schema.put("required", Arrays.asList(required));
    }
    Map<String, Object> tool = new LinkedHashMap<>();
    tool.put("name", name);
    tool.put("description", description);
    tool.put("inputSchema", schema);
    Map<String, Object> annotations = new LinkedHashMap<>();
    annotations.put("readOnlyHint", true);
    annotations.put("openWorldHint", false);
    tool.put("annotations", annotations);
    return tool;
  }

  private static Map.Entry<String, Object> maxRows() {
    return prop("max_rows", "integer", "Maximum rows, or tree lines, to return (default 100)");
  }

  private static Map.Entry<String, Object> maxChildren() {
    return prop("max_children", "integer", "Children shown per tree node (default 20)");
  }

  private static Map.Entry<String, Object> timeout() {
    return prop("timeout_seconds", "integer", "Stop the call after this many seconds (default 300)");
  }

  @SafeVarargs
  private static Map<String, Object> properties(Map.Entry<String, Object>... props) {
    Map<String, Object> properties = new LinkedHashMap<>();
    for (Map.Entry<String, Object> prop : props) {
      properties.put(prop.getKey(), prop.getValue());
    }
    return properties;
  }

  private static Map.Entry<String, Object> prop(String name, String type, String description) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", type);
    schema.put("description", description);
    return new java.util.AbstractMap.SimpleImmutableEntry<>(name, Collections.unmodifiableMap(schema));
  }
}
