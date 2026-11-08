package com.github.vlsi.mat.calcite.mcp;

import com.github.vlsi.mat.calcite.mcp.McpServer.ToolException;

import org.eclipse.mat.snapshot.ISnapshot;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Tools that each run one MAT command with explicit, validated options, so an assistant need not know MAT's command
 * line. Every value that goes into a command line is checked first: a space, a quote or a leading dash could add an
 * option of its own (a file to write, say), and {@link ObjectTools#checkAllowed} refuses such commands again before
 * they run.
 */
final class MatCommandTools {
  /** What a name in "class:field" form for excluded references may be made of. */
  private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9_.$\\[\\]]+:[A-Za-z0-9_$]+");
  private static final Pattern FIELD = Pattern.compile("[A-Za-z0-9_$.]+");

  // The "Path To GC Roots" menu of Eclipse MAT (Path2GCRootsQuery and MultiplePath2GCRootsQuery): which references
  // to leave out when looking for paths. The lists are MAT's own, entry by entry.
  private static final String WEAK = "java.lang.ref.WeakReference:referent "
      + "java.lang.ref.Finalizer:referent,unfinalized java.lang.Runtime:<Unfinalized>";
  private static final String SOFT = "java.lang.ref.SoftReference:referent";
  private static final String PHANTOM = "java.lang.ref.PhantomReference:referent";
  private static final String ALL_KINDS = "java.lang.ref.Reference:referent java.lang.ref.Finalizer:unfinalized "
      + "java.lang.Runtime:<Unfinalized>";
  /** The values of the references option, in the order of MAT's menu, with the -excludes of each. */
  private static final String[][] REFERENCE_MENU = {
      {"all", "\"\""}, // with all references
      {"exclude_weak", WEAK}, // exclude weak references
      {"exclude_soft", SOFT}, // exclude soft references
      {"exclude_phantom", PHANTOM}, // exclude phantom references
      {"exclude_weak_soft", WEAK + " " + SOFT}, // exclude weak/soft references
      {"exclude_phantom_soft", PHANTOM + " " + SOFT}, // exclude phantom/soft references
      {"exclude_phantom_weak", PHANTOM + " " + WEAK}, // exclude phantom/weak references
      {"exclude_all", ALL_KINDS}}; // exclude all phantom/weak/soft etc. references

  private MatCommandTools() {
  }

  /**
   * The {@code -excludes} option for a value of the references argument, as in MAT's Path To GC Roots menu. The
   * default is the last entry, which MAT's own default of the command matches.
   */
  static String excludesOption(String references) throws ToolException {
    String[] names = new String[REFERENCE_MENU.length];
    for (int i = 0; i < names.length; i++) {
      names[i] = REFERENCE_MENU[i][0];
    }
    String wanted = choice(references, "references", "exclude_all", names);
    for (String[] entry : REFERENCE_MENU) {
      if (entry[0].equals(wanted)) {
        return "-excludes " + entry[1];
      }
    }
    throw new IllegalStateException(wanted);
  }

  /** Whether the references value follows every reference, i.e. leaves nothing out. */
  static boolean followsAllReferences(String references) {
    return references != null && "all".equals(references.trim().toLowerCase(Locale.ROOT));
  }

  // ---------------------------------------------------------------- why something is alive

  /** merge_shortest_paths: the paths from GC roots to many objects, merged into one tree. */
  static String mergePaths(ISnapshot snapshot, String addresses, String classRegex, String groupBy,
                           String references, MatResultRenderer renderer, Cancellation cancellation)
      throws Exception {
    String objects = objects(snapshot, addresses, classRegex);
    String grouping = choice(groupBy, "group_by", "from_gc_roots", "from_gc_roots", "from_gc_roots_by_class",
        "from_objects_by_class").toUpperCase(Locale.ROOT);
    String result = run(snapshot, "merge_shortest_paths " + objects + " -groupBy " + grouping + " "
        + excludesOption(references), renderer, cancellation);
    return followsAllReferences(references) || !result.contains("\n0 rows") && !result.startsWith("0 rows") ? result
        : result + "\n\nNo path from a GC root with the references left out by references=" + referencesName(references)
            + ": the objects are only reachable through those references, or are garbage. Retry with references "
            + "all.";
  }

  /** gc_roots: the GC roots by type and the classes under each. */
  static String gcRoots(ISnapshot snapshot, MatResultRenderer renderer, Cancellation cancellation) throws Exception {
    return run(snapshot, "gc_roots", renderer, cancellation);
  }

  /** class_references: who references all instances of the classes, or what they reference, grouped by class. */
  static String classReferences(ISnapshot snapshot, String classRegex, String direction, MatResultRenderer renderer,
                                Cancellation cancellation) throws Exception {
    boolean incoming = "incoming".equals(choice(direction, "direction", "outgoing", "outgoing", "incoming"));
    return run(snapshot, "class_references " + classPattern(classRegex, "class_regex") + (incoming ? " -inbound" : ""),
        renderer, cancellation);
  }

  /** big_drops_in_dominator_tree: objects where the retained heap drops sharply from parent to children. */
  static String bigDrops(ISnapshot snapshot, String skip, MatResultRenderer renderer, Cancellation cancellation)
      throws Exception {
    String option;
    if (skip == null || skip.trim().isEmpty()) {
      option = ""; // MAT skips java.*, javax.*, com.sun.*, sun.* and jdk.* dominators
    } else if ("none".equalsIgnoreCase(skip.trim())) {
      option = " -skip ^$"; // matches no class name
    } else {
      option = " -skip " + classPattern(skip, "skip");
    }
    return run(snapshot, "big_drops_in_dominator_tree" + option, renderer, cancellation);
  }

  /** unreachable_objects: objects that no GC root reaches, per class. */
  static String unreachableObjects(ISnapshot snapshot, MatResultRenderer renderer, Cancellation cancellation)
      throws Exception {
    return run(snapshot, "unreachable_objects", renderer, cancellation);
  }

  // ---------------------------------------------------------------- references

  /** The reference-statistics commands: what weak, soft, phantom and finalizer references hold. */
  static String referenceStatistics(ISnapshot snapshot, String kind, String classRegex, MatResultRenderer renderer,
                                    Cancellation cancellation) throws Exception {
    String wanted = choice(kind, "kind", "weak", "weak", "soft", "phantom", "finalizer", "custom");
    String command;
    if ("custom".equals(wanted)) {
      command = "references_statistics " + classPattern(classRegex, "class_regex (needed for kind custom)");
    } else {
      if (classRegex != null && !classRegex.trim().isEmpty()) {
        throw new ToolException("class_regex is only used with kind custom");
      }
      command = wanted + "_references_statistics";
    }
    return run(snapshot, command, renderer, cancellation);
  }

  // ---------------------------------------------------------------- collections, strings, classes

  private static String referencesName(String references) {
    return references == null || references.trim().isEmpty() ? "exclude_all" : references.trim();
  }

  /** The distributions of the collection and array commands. */
  static String collectionStats(ISnapshot snapshot, String classRegex, String kind, Integer segments,
                                MatResultRenderer renderer, Cancellation cancellation) throws Exception {
    String wanted = choice(kind, "kind", null, "fill_ratio", "collision_ratio", "array_fill_ratio",
        "collection_sizes", "array_sizes");
    String regex = classPattern(classRegex, "class_regex");
    String command;
    boolean buckets = true;
    switch (wanted) {
      case "fill_ratio":
        command = "collection_fill_ratio";
        break;
      case "collision_ratio":
        command = "map_collision_ratio";
        break;
      case "array_fill_ratio":
        command = "array_fill_ratio";
        break;
      case "collection_sizes":
        command = "collections_grouped_by_size";
        buckets = false;
        break;
      default:
        command = "arrays_grouped_by_size";
        buckets = false;
        break;
    }
    if (segments != null && !buckets) {
      throw new ToolException("segments applies to fill_ratio, collision_ratio and array_fill_ratio only");
    }
    return run(snapshot, command + " " + regex + (segments == null ? "" : " -segments " + segments), renderer,
        cancellation);
  }

  /** group_by_value: objects of a class grouped by their text, or by the value of one field. */
  static String groupByValue(ISnapshot snapshot, String classRegex, String field, MatResultRenderer renderer,
                             Cancellation cancellation) throws Exception {
    String option = "";
    if (field != null && !field.trim().isEmpty()) {
      if (!FIELD.matcher(field.trim()).matches()) {
        throw new ToolException("field must be a field name such as size or table.length, got " + field);
      }
      option = " -field " + field.trim();
    }
    return run(snapshot, "group_by_value " + classPattern(classRegex, "class_regex") + option, renderer,
        cancellation);
  }

  /** find_strings: strings whose whole text matches a regular expression. */
  static String findStrings(ISnapshot snapshot, String pattern, String classRegex, MatResultRenderer renderer,
                            Cancellation cancellation) throws Exception {
    String regex = classRegex == null || classRegex.trim().isEmpty() ? "java.lang.String"
        : classPattern(classRegex, "class_regex");
    return run(snapshot, "find_strings " + regex + " -pattern " + classPattern(pattern, "pattern"), renderer,
        cancellation);
  }

  /** duplicate_classes: classes defined more than once, by different class loaders. */
  static String duplicateClasses(ISnapshot snapshot, MatResultRenderer renderer, Cancellation cancellation)
      throws Exception {
    return run(snapshot, "duplicate_classes", renderer, cancellation);
  }

  /** system_properties: the System properties of the dumped JVM, optionally only those with a text in key or value. */
  static String systemProperties(ISnapshot snapshot, String filter, MatResultRenderer renderer,
                                 Cancellation cancellation) throws Exception {
    String all = run(snapshot, "system_properties", renderer, cancellation);
    if (filter == null || filter.trim().isEmpty()) {
      return all;
    }
    String needle = filter.trim().toLowerCase(Locale.ROOT);
    StringBuilder sb = new StringBuilder();
    int rows = 0;
    String[] lines = all.split("\n");
    for (int i = 0; i < lines.length; i++) {
      boolean row = i >= 2 && lines[i].startsWith("|");
      if (!row) {
        if (i < 2) {
          sb.append(lines[i]).append('\n');
        }
        continue;
      }
      if (lines[i].toLowerCase(Locale.ROOT).contains(needle)) {
        sb.append(lines[i]).append('\n');
        rows++;
      }
    }
    return sb.append('\n').append(rows).append(rows == 1 ? " row" : " rows").append(" containing \"")
        .append(filter.trim()).append('"').toString();
  }

  /** oql: an OQL query, MAT's own query language. */
  static String oql(ISnapshot snapshot, String query, MatResultRenderer renderer, Cancellation cancellation)
      throws Exception {
    if (query == null || query.trim().isEmpty()) {
      throw new ToolException("Missing argument: query");
    }
    // MAT reads the command line with quotes and backslash escapes: the query is one quoted argument
    String quoted = "\"" + query.trim().replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("\\s+", " ") + "\"";
    return run(snapshot, "oql " + quoted, renderer, cancellation);
  }

  // ---------------------------------------------------------------- retained set

  /**
   * customized_retained_set: what the objects keep alive when the given references are not followed, e.g.
   * java.lang.ref.Reference:referent to ignore weak references. The command has an optional file option that
   * {@link ObjectTools#matQuery} refuses; this builds the command line from validated references only.
   */
  static String retainedSetWithout(ISnapshot snapshot, String addresses, String excludedReferences,
                                   MatResultRenderer renderer, Cancellation cancellation) throws Exception {
    List<String> references = Arrays.asList(excludedReferences.trim().split("[\\s,]+"));
    for (String reference : references) {
      if (!REFERENCE.matcher(reference).matches()) {
        throw new ToolException("excluded_references must be class:field names separated by spaces or commas, e.g. "
            + "java.lang.ref.Reference:referent, got " + reference);
      }
    }
    return ObjectTools.run(snapshot, "customized_retained_set " + ObjectTools.addressArgs(snapshot, addresses)
        + " -x " + String.join(" ", references), renderer, cancellation);
  }

  // ---------------------------------------------------------------- helpers

  /** Runs a command after the same checks as mat_query. */
  private static String run(ISnapshot snapshot, String command, MatResultRenderer renderer,
                            Cancellation cancellation) throws Exception {
    ObjectTools.checkAllowed(command, snapshot);
    return ObjectTools.run(snapshot, command, renderer, cancellation);
  }

  /** Addresses or a class-name regular expression, as the objects of a command. */
  private static String objects(ISnapshot snapshot, String addresses, String classRegex) throws Exception {
    boolean hasAddresses = addresses != null && !addresses.trim().isEmpty();
    boolean hasClass = classRegex != null && !classRegex.trim().isEmpty();
    if (hasAddresses == hasClass) {
      throw new ToolException("Give either address (one or more objects) or class_regex (all instances of "
          + "matching classes), not both or neither");
    }
    return hasAddresses ? ObjectTools.addressArgs(snapshot, addresses) : classPattern(classRegex, "class_regex");
  }

  /**
   * A regular expression for one command-line argument. MAT matches class names against the whole name, so
   * {@code java.util.Hash.*} matches HashMap and its inner classes.
   */
  static String classPattern(String value, String argument) throws ToolException {
    String pattern = value == null ? "" : value.trim();
    if (pattern.isEmpty()) {
      throw new ToolException("Missing argument: " + argument);
    }
    if (pattern.matches("(?s).*[\\s\"'].*")) {
      throw new ToolException(argument + " must not contain spaces or quotes (write \\s for a space), got " + value);
    }
    if (pattern.startsWith("-")) {
      throw new ToolException(argument + " must not start with a dash, got " + value);
    }
    try {
      Pattern.compile(pattern);
    } catch (PatternSyntaxException e) {
      throw new ToolException(argument + " is not a valid regular expression: " + e.getDescription());
    }
    return pattern;
  }

  /** One of the allowed values, ignoring case; the default (if there is one) for an empty value. */
  static String choice(String value, String argument, String defaultValue, String... allowed) throws ToolException {
    String wanted = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    if (wanted.isEmpty() && defaultValue != null) {
      return defaultValue;
    }
    for (String option : allowed) {
      if (option.equals(wanted)) {
        return option;
      }
    }
    throw new ToolException(argument + " must be " + String.join(", ", Arrays.asList(allowed).subList(0,
        allowed.length - 1)) + " or " + allowed[allowed.length - 1] + (wanted.isEmpty() ? "" : ", got " + value));
  }
}
