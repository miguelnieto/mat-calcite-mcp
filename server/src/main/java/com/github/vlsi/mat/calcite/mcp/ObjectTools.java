package com.github.vlsi.mat.calcite.mcp;

import com.github.vlsi.mat.calcite.mcp.McpServer.ToolException;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.query.IResult;
import org.eclipse.mat.query.annotations.Argument;
import org.eclipse.mat.query.annotations.descriptors.IArgumentDescriptor;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.Field;
import org.eclipse.mat.snapshot.model.GCRootInfo;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IInstance;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IObjectArray;
import org.eclipse.mat.snapshot.model.IPrimitiveArray;
import org.eclipse.mat.snapshot.model.ObjectReference;
import org.eclipse.mat.snapshot.query.SnapshotQuery;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The per-object actions of MAT's context menu, for {@link McpServer}: object details, references, retained set,
 * path to GC roots and immediate dominators, plus any other MAT command. Objects are given by address.
 */
final class ObjectTools {
  private static final int MAX_FIELDS = 100;
  private static final int MAX_ARRAY_ELEMENTS = 20;

  private ObjectTools() {
  }

  static String objectInfo(ISnapshot snapshot, String address) throws Exception {
    int[] ids = objectIds(snapshot, address);
    if (ids.length != 1) {
      throw new ToolException("object_info takes one address, got " + ids.length);
    }
    int id = ids[0];
    IObject object = snapshot.getObject(id);
    StringBuilder sb = new StringBuilder();
    sb.append("object: ").append(object.getDisplayName()).append('\n');
    sb.append("class: ").append(object.getClazz().getName()).append('\n');
    sb.append("shallow size: ").append(object.getUsedHeapSize()).append(" bytes\n");
    sb.append("retained size: ").append(snapshot.getRetainedHeapSize(id)).append(" bytes\n");
    GCRootInfo[] roots = snapshot.getGCRootInfo(id);
    if (roots != null && roots.length > 0) {
      sb.append("GC root: ").append(GCRootInfo.getTypeSetAsString(roots)).append('\n');
    }
    int dominator = snapshot.getImmediateDominatorId(id);
    if (dominator >= 0) {
      sb.append("immediate dominator: ").append(snapshot.getObject(dominator).getDisplayName()).append('\n');
    }

    if (object instanceof IClass) {
      IClass clazz = (IClass) object;
      sb.append("instances: ").append(clazz.getNumberOfObjects()).append('\n');
      appendFields(sb, "static fields", clazz.getStaticFields());
    } else if (object instanceof IInstance) {
      appendFields(sb, "fields", ((IInstance) object).getFields());
    } else if (object instanceof IObjectArray) {
      IObjectArray array = (IObjectArray) object;
      sb.append("length: ").append(array.getLength()).append('\n');
      int shown = Math.min(array.getLength(), MAX_ARRAY_ELEMENTS);
      long[] refs = array.getReferenceArray(0, shown);
      sb.append("elements:\n");
      for (int i = 0; i < shown; i++) {
        sb.append("  [").append(i).append("] ").append(describeReference(snapshot, refs[i])).append('\n');
      }
      if (array.getLength() > shown) {
        sb.append("  ... ").append(array.getLength() - shown).append(" more\n");
      }
    } else if (object instanceof IPrimitiveArray) {
      IPrimitiveArray array = (IPrimitiveArray) object;
      sb.append("length: ").append(array.getLength()).append('\n');
      int shown = Math.min(array.getLength(), MAX_ARRAY_ELEMENTS);
      sb.append("elements:");
      for (int i = 0; i < shown; i++) {
        sb.append(i == 0 ? " " : ", ").append(array.getValueAt(i));
      }
      sb.append(array.getLength() > shown ? ", ..." : "").append('\n');
    }
    return sb.toString();
  }

  private static void appendFields(StringBuilder sb, String title, List<Field> fields) {
    sb.append(title).append(":\n");
    int shown = 0;
    for (Field field : fields) {
      if (shown == MAX_FIELDS) {
        sb.append("  ... ").append(fields.size() - shown).append(" more\n");
        break;
      }
      Object value = field.getValue();
      String text = value instanceof ObjectReference ? describeReference((ObjectReference) value)
          : String.valueOf(value);
      sb.append("  ").append(field.getName()).append(" = ").append(text).append('\n');
      shown++;
    }
  }

  private static String describeReference(ObjectReference reference) {
    try {
      return reference.getObject().getDisplayName();
    } catch (SnapshotException e) {
      return "0x" + Long.toHexString(reference.getObjectAddress());
    }
  }

  private static String describeReference(ISnapshot snapshot, long address) {
    if (address == 0) {
      return "null";
    }
    try {
      return snapshot.getObject(snapshot.mapAddressToId(address)).getDisplayName();
    } catch (SnapshotException e) {
      return "0x" + Long.toHexString(address);
    }
  }

  /** "list_objects": who references the objects (incoming) or what they reference (outgoing). */
  static String references(ISnapshot snapshot, String addresses, boolean incoming, MatResultRenderer renderer,
                           Cancellation cancellation) throws Exception {
    return run(snapshot, "list_objects " + (incoming ? "-inbound " : "") + addressArgs(snapshot, addresses),
        renderer, cancellation);
  }

  /** @param excludedReferences class:field references not to follow (java.lang.ref.Reference:referent), or empty */
  static String retainedSet(ISnapshot snapshot, String addresses, String excludedReferences,
                            MatResultRenderer renderer, Cancellation cancellation) throws Exception {
    if (excludedReferences != null && !excludedReferences.trim().isEmpty()) {
      return MatCommandTools.retainedSetWithout(snapshot, addresses, excludedReferences, renderer, cancellation);
    }
    return run(snapshot, "show_retained_set " + addressArgs(snapshot, addresses), renderer, cancellation);
  }

  /**
   * @param references which references to leave out, as in MAT's Path To GC Roots menu: all, exclude_weak,
   *                   exclude_soft, exclude_phantom, exclude_weak_soft, exclude_phantom_soft, exclude_phantom_weak or
   *                   exclude_all (the default)
   */
  static String pathToGcRoots(ISnapshot snapshot, String address, int paths, String references,
                              MatResultRenderer renderer, Cancellation cancellation) throws Exception {
    int[] ids = objectIds(snapshot, address);
    if (ids.length != 1) {
      throw new ToolException("path_to_gc_roots takes one address, got " + ids.length);
    }
    String result = run(snapshot, "path2gc -numberOfPaths " + paths + " " + toAddress(snapshot, ids[0]) + " "
        + MatCommandTools.excludesOption(references), renderer, cancellation);
    // MAT shows just the object when no path remains once those references are left out; a GC root looks the same
    return MatCommandTools.followsAllReferences(references) || !result.endsWith("\n1 row") ? result : result
        + "\n\nOnly the object itself: either it is a GC root, or it is reachable only through references that the "
        + "references option leaves out (by default weak, soft, phantom and finalizer references). Retry with "
        + "references all to see how it is reachable.";
  }

  /**
   * @param skip regular expression of dominator classes to step over, e.g. "java.*" for the first dominator that is
   *             not a JDK class; empty for the true immediate dominators. MAT itself skips java.* by default.
   */
  static String immediateDominators(ISnapshot snapshot, String addresses, String skip, MatResultRenderer renderer,
                                    Cancellation cancellation) throws Exception {
    String pattern = skip == null || skip.trim().isEmpty() ? "^$" : skip.trim(); // "^$" matches no class name
    if (pattern.matches(".*\\s.*")) {
      throw new ToolException("skip must be one regular expression without spaces, e.g. java\\..*|sun\\..*");
    }
    try {
      java.util.regex.Pattern.compile(pattern);
    } catch (java.util.regex.PatternSyntaxException e) {
      throw new ToolException("skip is not a valid regular expression: " + e.getDescription());
    }
    return run(snapshot, "immediate_dominators -skip " + pattern + " " + addressArgs(snapshot, addresses), renderer,
        cancellation);
  }

  /** The values of dominator_tree's group_by, as MAT's -groupBy names. */
  private static final String[][] DOMINATOR_GROUPINGS = {
      {"none", "NONE"}, {"class", "BY_CLASS"}, {"class_loader", "BY_CLASSLOADER"}, {"package", "BY_PACKAGE"}};

  /**
   * "dominator_tree": the objects that keep the most memory alive, as MAT's dominator tree. With an address, what that
   * one object dominates; otherwise the whole heap, either as single objects or summed by class, class loader or
   * package.
   */
  static String dominatorTree(ISnapshot snapshot, String address, String groupBy, MatResultRenderer renderer,
                              Cancellation cancellation) throws Exception {
    boolean hasAddress = address != null && !address.trim().isEmpty();
    boolean hasGrouping = groupBy != null && !groupBy.trim().isEmpty();
    if (hasAddress) {
      if (hasGrouping) {
        throw new ToolException("address and group_by cannot be combined: group_by summarizes the whole heap, "
            + "address shows what one object dominates");
      }
      return run(snapshot, "show_dominator_tree " + addressArgs(snapshot, address), renderer, cancellation);
    }
    String grouping = null;
    String wanted = hasGrouping ? groupBy.trim().toLowerCase(java.util.Locale.ROOT) : "none";
    for (String[] option : DOMINATOR_GROUPINGS) {
      if (option[0].equals(wanted)) {
        grouping = option[1];
      }
    }
    if (grouping == null) {
      throw new ToolException("group_by must be none, class, class_loader or package, got " + groupBy);
    }
    return run(snapshot, "dominator_tree -groupBy " + grouping, renderer, cancellation);
  }

  /**
   * "threads": all threads with state and retained heap, largest first (thread_overview; the renderer's depth decides
   * whether the stack frames show), or the details of the given threads (thread_details).
   */
  static String threads(ISnapshot snapshot, String addresses, MatResultRenderer renderer, Cancellation cancellation)
      throws Exception {
    if (addresses == null || addresses.trim().isEmpty()) {
      return run(snapshot, "thread_overview", renderer.sortedBy("Retained Heap"), cancellation);
    }
    for (int id : objectIds(snapshot, addresses)) {
      IObject object = snapshot.getObject(id);
      if (!(object instanceof IInstance) || !object.getClazz().doesExtend("java.lang.Thread")) {
        throw new ToolException(object.getDisplayName() + " is not a thread. Call threads without address to list "
            + "the threads, or use sql_query on instanceof.\"java.lang.Thread\".");
      }
    }
    return run(snapshot, "thread_details " + addressArgs(snapshot, addresses), renderer, cancellation);
  }

  /**
   * Any MAT command line, e.g. "histogram" or "find_leaks", except commands that write files or run reports: the
   * assistant also reads heap contents, which may hold text written to steer it, so it must not get a way to write
   * files through MAT (export_hprof -output, create_report...). export_query is the one tool that writes, under its
   * own rules.
   */
  static String matQuery(ISnapshot snapshot, String command, MatResultRenderer renderer, Cancellation cancellation)
      throws Exception {
    checkAllowed(command, snapshot);
    return run(snapshot, command, renderer, cancellation);
  }

  /**
   * Refuses MAT commands that write files, run reports or open other dumps. Applies to {@code mat_query} and to the
   * commands that MAT's own report results name.
   */
  static void checkAllowed(String command, ISnapshot snapshot) throws ToolException {
    String name = command.trim().split("\\s+", 2)[0];
    if (REPORT_COMMANDS.contains(name)) {
      throw new ToolException(name + " runs a MAT report, which writes files; mat_query does not run reports. Use "
          + "leak_suspects, heap_overview or the other tools instead.");
    }
    SnapshotQuery query;
    try {
      query = SnapshotQuery.lookup(name, snapshot);
    } catch (SnapshotException e) {
      throw new ToolException("Unknown MAT command: " + name + " (" + e.getMessage() + ")");
    }
    for (IArgumentDescriptor argument : query.getArguments()) {
      Class<?> type = argument.getType();
      // SECONDARY_SNAPSHOT (e.g. delta_histogram -baseline <path>) opens any path as a dump, which writes index
      // files beside it and deletes MAT's older ones there
      if (File.class.equals(type) || File[].class.equals(type) || argument.getAdvice() == Argument.Advice.SAVE
          || argument.getAdvice() == Argument.Advice.DIRECTORY
          || argument.getAdvice() == Argument.Advice.SECONDARY_SNAPSHOT) {
        throw new ToolException(name + " reads or writes files (argument " + argument.getName() + "), which "
            + "mat_query does not allow. Use export_query to write a CSV, compare_dumps to compare dumps.");
      }
    }
  }

  /** Commands that run report definitions: they write their output to files and run any query they list. */
  private static final Set<String> REPORT_COMMANDS = new HashSet<>(Arrays.asList("create_report", "default_report"));

  static String run(ISnapshot snapshot, String commandLine, MatResultRenderer renderer,
                            Cancellation cancellation) throws Exception {
    IResult result = SnapshotQuery.parse(commandLine, snapshot).execute(cancellation.listener());
    return renderer.withListener(cancellation.listener()).render(snapshot, result);
  }

  /** Addresses checked against the dump and formatted for a MAT command line. */
  static String addressArgs(ISnapshot snapshot, String addresses) throws Exception {
    StringBuilder sb = new StringBuilder();
    for (int id : objectIds(snapshot, addresses)) {
      sb.append(sb.length() == 0 ? "" : " ").append(toAddress(snapshot, id));
    }
    return sb.toString();
  }

  private static String toAddress(ISnapshot snapshot, int id) throws SnapshotException {
    return "0x" + Long.toHexString(snapshot.mapIdToAddress(id));
  }

  /** Object ids for addresses separated by spaces or commas, each hex (0x...) or decimal. */
  static int[] objectIds(ISnapshot snapshot, String addresses) throws ToolException {
    String[] tokens = addresses.trim().split("[\\s,]+");
    int[] ids = new int[tokens.length];
    for (int i = 0; i < tokens.length; i++) {
      String token = tokens[i];
      long address;
      try {
        address = token.startsWith("0x") || token.startsWith("0X") ? Long.parseUnsignedLong(token.substring(2), 16)
            : Long.parseLong(token);
      } catch (NumberFormatException e) {
        throw new ToolException("Not an address: " + token + " (use 0x... as shown in results)");
      }
      try {
        ids[i] = snapshot.mapAddressToId(address);
      } catch (SnapshotException e) {
        throw new ToolException("No object at address " + token);
      }
    }
    return ids;
  }
}
