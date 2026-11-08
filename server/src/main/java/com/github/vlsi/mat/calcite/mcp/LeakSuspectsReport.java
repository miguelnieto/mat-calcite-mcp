package com.github.vlsi.mat.calcite.mcp;

import com.github.vlsi.mat.calcite.QueryCancellation;

import net.minidev.json.JSONStyle;
import net.minidev.json.JSONValue;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.inspections.collectionextract.AbstractExtractedCollection;
import org.eclipse.mat.inspections.collectionextract.CollectionExtractionUtils;
import org.eclipse.mat.inspections.collectionextract.ExtractedCollection;
import org.eclipse.mat.inspections.collectionextract.ExtractedMap;
import org.eclipse.mat.query.ContextProvider;
import org.eclipse.mat.query.IContextObject;
import org.eclipse.mat.query.IContextObjectSet;
import org.eclipse.mat.query.IResult;
import org.eclipse.mat.query.IResultTable;
import org.eclipse.mat.snapshot.ClassHistogramRecord;
import org.eclipse.mat.snapshot.Histogram;
import org.eclipse.mat.snapshot.IPathsFromGCRootsComputer;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.GCRootInfo;
import org.eclipse.mat.snapshot.model.IArray;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IStackFrame;
import org.eclipse.mat.snapshot.model.IThreadStack;
import org.eclipse.mat.snapshot.model.NamedReference;
import org.eclipse.mat.snapshot.query.SnapshotQuery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The leak suspects of a heap dump, built on MAT's {@code find_leaks} and shown as text or as JSON.
 *
 * <p>MAT's own Leak Suspects report is a set of HTML pages with the description in prose. This builds one data model
 * of the same facts and more, and renders it two ways: text for reading, and JSON with the raw numbers, so that an
 * assistant can word the findings itself. Besides what MAT reports (the suspect, its retained heap, the accumulation
 * point, the paths from a GC root, what the memory is made of by class, what the suspect dominates) the model has
 * the biggest instances of a group, the stack frames and local variables that hold a thread's memory, and the facts
 * of the collection at the accumulation point (size, capacity, fill and collision ratio, a few entries).
 */
final class LeakSuspectsReport {
  private static final int BIGGEST_INSTANCES = 5;
  private static final int MAX_FRAMES = 40;
  private static final int LOCALS_PER_FRAME = 3;
  private static final int MAX_CLASSES = 10;
  private static final int MAX_DOMINATED = 5;
  private static final int MAX_SAMPLES = 3;
  /** A path longer than this keeps its first and last halves: a linked list leak has a path as long as the list. */
  private static final int MAX_PATH_NODES = 40;
  private static final int MAX_LABEL = 120;
  /** The references MAT ignores in its Path To GC Roots menu entry "exclude all phantom/weak/soft etc.". */
  private static final String[] IGNORED_REFERENCES = {
      "java.lang.ref.Reference:referent", "java.lang.ref.Finalizer:unfinalized", "java.lang.Runtime:<Unfinalized>"};

  private LeakSuspectsReport() {
  }

  /** @param json the raw numbers as one JSON document, instead of text */
  static String run(ISnapshot snapshot, int thresholdPercent, int maxSuspects, boolean json,
                    Cancellation cancellation) throws Exception {
    Map<String, Object> model = build(snapshot, thresholdPercent, maxSuspects, cancellation);
    // LT_COMPRESS is plain JSON that does not escape "/" (paths, class names), which saves tokens
    return json ? JSONValue.toJSONString(model, JSONStyle.LT_COMPRESS) : toText(model);
  }

  // ---------------------------------------------------------------- the model

  /**
   * Uses only public API: MAT's find_leaks result is an {@code IResultTable} whose columns are suspect, number of
   * objects, retained heap, share, accumulation point and its size and share, and whose context providers give the
   * suspect (a set for a group of objects) and the accumulation point. ({@code FindLeaksQuery} itself lives in a
   * friend-restricted package.)
   */
  static Map<String, Object> build(ISnapshot snapshot, int thresholdPercent, int maxSuspects,
                                   Cancellation cancellation) throws Exception {
    IResult result = SnapshotQuery.lookup("find_leaks", snapshot)
        .setArgument("threshold_percent", thresholdPercent)
        .execute(cancellation.listener());
    long total = snapshot.getSnapshotInfo().getUsedHeapSize();
    Map<String, Object> model = new LinkedHashMap<>();
    model.put("dump", snapshot.getSnapshotInfo().getPath());
    model.put("usedHeapBytes", total);
    model.put("thresholdPercent", thresholdPercent);
    if (!(result instanceof IResultTable) || ((IResultTable) result).getRowCount() == 0) {
      model.put("suspectsFound", 0);
      model.put("suspects", new ArrayList<>());
      model.put("message", "No leak suspect retains more than " + thresholdPercent + "% of the heap (" + total
          + " bytes). Lower threshold_percent, or look at heap_overview for the biggest objects.");
      return model;
    }
    IResultTable table = (IResultTable) result;
    List<ContextProvider> contexts = table.getResultMetaData() == null ? null
        : table.getResultMetaData().getContextProviders();
    int shown = Math.min(maxSuspects, table.getRowCount());
    model.put("suspectsFound", table.getRowCount());
    List<Map<String, Object>> suspects = new ArrayList<>();
    Map<IClass, Set<String>> ignored = ignoredReferences(snapshot);
    for (int i = 0; i < shown; i++) {
      suspects.add(suspect(snapshot, table, contexts, i, total, ignored, cancellation));
    }
    model.put("suspects", suspects);
    return model;
  }

  private static Map<String, Object> suspect(ISnapshot snapshot, IResultTable table, List<ContextProvider> contexts,
                                             int index, long total, Map<IClass, Set<String>> ignored,
                                             Cancellation cancellation) throws Exception {
    Object row = table.getRow(index);
    long objects = AnalysisTools.number(table.getColumnValue(row, 1));
    long retained = AnalysisTools.number(table.getColumnValue(row, 2));
    IContextObject suspect = contexts != null && contexts.size() > 0 ? contexts.get(0).getContext(row)
        : table.getContext(row);
    IContextObject point = contexts != null && contexts.size() > 1 ? contexts.get(1).getContext(row) : null;
    boolean group = suspect instanceof IContextObjectSet && objects > 1;
    IObject first = snapshot.getObject(suspect.getObjectId());
    int[] suspectIds = group ? ((IContextObjectSet) suspect).getObjectIds() : new int[] {first.getObjectId()};
    IObject accumulation = point != null && point.getObjectId() >= 0 ? snapshot.getObject(point.getObjectId()) : null;
    List<String> notes = new ArrayList<>();

    Map<String, Object> s = new LinkedHashMap<>();
    s.put("rank", index + 1);
    s.put("kind", group ? "group" : "object");
    s.put("label", group ? objects + " instances of " + AnalysisTools.className(first) : first.getDisplayName());
    s.put("className", AnalysisTools.className(first));
    s.put("address", group ? null : AnalysisTools.hex(snapshot, first.getObjectId()));
    s.put("objects", objects);
    s.put("retainedBytes", retained);
    s.put("retainedPercent", percent(retained, total));
    s.put("classLoader", classLoader(snapshot, first));
    s.put("accumulationPoint", accumulation == null ? null : summary(snapshot, accumulation, total));
    if (accumulation == null && group) {
      notes.add("No single object holds the group; each instance is kept alive on its own.");
    }

    Path fromRoot = null;
    if (!group) {
      fromRoot = pathFromRoot(snapshot, first.getObjectId(), ignored, notes, "the suspect");
    }
    s.put("pathFromGcRoot", fromRoot == null ? null : fromRoot.nodes);
    Path toPoint = null;
    if (accumulation != null && accumulation.getObjectId() != first.getObjectId()) {
      toPoint = pathFromRoot(snapshot, accumulation.getObjectId(), ignored, notes, "the accumulation point");
    }
    s.put("pathToAccumulationPoint", toPoint == null ? null : toPoint.nodes);

    s.put("retainedByClass", retainedByClass(snapshot, suspectIds, retained, cancellation));
    s.put("dominates", group ? null : dominates(snapshot, first.getObjectId(), total));
    s.put("biggestInstances", group ? biggestInstances(snapshot, suspectIds) : null);
    s.put("collection", collectionFacts(snapshot, accumulation, toPoint));

    // A thread's memory is held by its stack: show which frames and local variables hold it
    IObject thread = isThread(first) ? first : accumulation != null && isThread(accumulation) ? accumulation : null;
    s.put("thread", thread == null ? null : thread(snapshot, thread));
    s.put("notes", notes);
    return s;
  }

  /** The object as a few facts, for paths and lists. */
  private static Map<String, Object> summary(ISnapshot snapshot, IObject object, long total) throws SnapshotException {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("label", label(object));
    m.put("address", AnalysisTools.hex(snapshot, object.getObjectId()));
    m.put("className", AnalysisTools.className(object));
    m.put("shallowBytes", object.getUsedHeapSize());
    long retained = snapshot.getRetainedHeapSize(object.getObjectId());
    m.put("retainedBytes", retained);
    m.put("retainedPercent", percent(retained, total));
    return m;
  }

  // ---------------------------------------------------------------- paths

  private static final class Path {
    final int[] ids;
    final List<Map<String, Object>> nodes;

    Path(int[] ids, List<Map<String, Object>> nodes) {
      this.ids = ids;
      this.nodes = nodes;
    }
  }

  /** The references MAT leaves out when it looks for a path from a GC root, as its menu entry "exclude all". */
  private static Map<IClass, Set<String>> ignoredReferences(ISnapshot snapshot) throws SnapshotException {
    Map<IClass, Set<String>> map = new HashMap<>();
    for (String entry : IGNORED_REFERENCES) {
      int colon = entry.indexOf(':');
      Set<String> fields = new HashSet<>();
      for (String field : entry.substring(colon + 1).split(",")) {
        fields.add(field);
        if (field.equals("<Unfinalized>")) { // MAT names the pseudo-field in the language of its UI
          fields.add('<' + GCRootInfo.getTypeAsString(GCRootInfo.Type.UNFINALIZED) + '>');
        }
      }
      for (IClass clazz : snapshot.getClassesByName(Pattern.compile(Pattern.quote(entry.substring(0, colon))), true)) {
        map.put(clazz, fields);
      }
    }
    return map;
  }

  /**
   * The shortest path from a GC root to the object, root first, each node with the reference that leads to it. Null,
   * with a note, when only weak, soft, phantom or finalizer references keep the object.
   */
  private static Path pathFromRoot(ISnapshot snapshot, int id, Map<IClass, Set<String>> ignored, List<String> notes,
                                   String what) throws SnapshotException {
    int[] toRoot = snapshot.getPathsFromGCRoots(id, ignored).getNextShortestPath();
    boolean noPath = toRoot == null || toRoot.length == 0;
    if (!noPath && toRoot.length == 1) { // just the object: only fine if it is a GC root itself
      GCRootInfo[] roots = snapshot.getGCRootInfo(id);
      noPath = roots == null || roots.length == 0;
    }
    if (noPath) {
      notes.add("No path from a GC root to " + what + " without weak, soft, phantom or finalizer references: only "
          + "such references keep it, so the JVM can free it under memory pressure. list_references with direction "
          + "incoming shows what references it.");
      return null;
    }
    int n = toRoot.length;
    int[] ids = new int[n];
    for (int i = 0; i < n; i++) {
      ids[i] = toRoot[n - 1 - i];
    }
    List<Map<String, Object>> nodes = new ArrayList<>();
    IObject previous = null;
    for (int i = 0; i < n; i++) {
      IObject object = snapshot.getObject(ids[i]);
      boolean skipped = n > MAX_PATH_NODES && i >= MAX_PATH_NODES / 2 && i < n - MAX_PATH_NODES / 2;
      if (skipped) {
        if (i == MAX_PATH_NODES / 2) {
          Map<String, Object> gap = new LinkedHashMap<>();
          gap.put("omittedNodes", n - MAX_PATH_NODES);
          nodes.add(gap);
        }
        previous = object;
        continue;
      }
      Map<String, Object> node = summary(snapshot, object, 0);
      node.remove("retainedPercent");
      if (i == 0) {
        GCRootInfo[] roots = snapshot.getGCRootInfo(ids[i]);
        node.put("gcRootTypes", roots == null || roots.length == 0 ? null : GCRootInfo.getTypeSetAsString(roots));
      } else {
        node.put("viaReference", referenceName(previous, ids[i]));
      }
      nodes.add(node);
      previous = object;
    }
    return new Path(ids, nodes);
  }

  /** The name of the reference by which the referrer holds the target: a field, an array index, or a GC root link. */
  private static String referenceName(IObject referrer, int targetId) throws SnapshotException {
    for (NamedReference reference : referrer.getOutboundReferences()) {
      if (reference.getObjectId() == targetId) {
        return reference.getName();
      }
    }
    return null;
  }

  // ---------------------------------------------------------------- what the memory is made of

  /**
   * The classes of everything the suspect retains, largest shallow heap first: MAT's "All Accumulated Objects". Like
   * MAT's leak report this is the minimum retained set, the objects each member keeps alive on its own, which is what
   * the retained size of a suspect counts. (For a group the full retained set also holds objects that only several
   * members share, and is larger.)
   */
  private static Map<String, Object> retainedByClass(ISnapshot snapshot, int[] suspectIds, long retained,
                                                     Cancellation cancellation) throws Exception {
    int[] retainedIds = snapshot.getMinRetainedSet(suspectIds, cancellation.listener());
    Histogram histogram = snapshot.getHistogram(retainedIds, cancellation.listener());
    List<ClassHistogramRecord> records = new ArrayList<>(histogram.getClassHistogramRecords());
    records.sort(Comparator.comparingLong(ClassHistogramRecord::getUsedHeapSize).reversed()
        .thenComparing(ClassHistogramRecord::getLabel));
    List<Map<String, Object>> classes = new ArrayList<>();
    long otherObjects = 0;
    long otherBytes = 0;
    for (int i = 0; i < records.size(); i++) {
      ClassHistogramRecord record = records.get(i);
      if (i < MAX_CLASSES) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("className", record.getLabel());
        c.put("objects", record.getNumberOfObjects());
        c.put("shallowBytes", record.getUsedHeapSize());
        c.put("sharePercent", percent(record.getUsedHeapSize(), retained));
        classes.add(c);
      } else {
        otherObjects += record.getNumberOfObjects();
        otherBytes += record.getUsedHeapSize();
      }
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("totalObjects", retainedIds.length);
    m.put("classes", classes);
    if (records.size() > MAX_CLASSES) {
      Map<String, Object> other = new LinkedHashMap<>();
      other.put("classes", records.size() - MAX_CLASSES);
      other.put("objects", otherObjects);
      other.put("shallowBytes", otherBytes);
      m.put("other", other);
    }
    return m;
  }

  /** The objects the suspect immediately dominates that retain the most: which of its fields or locals hold it. */
  private static Map<String, Object> dominates(ISnapshot snapshot, int id, long total) throws SnapshotException {
    int[] dominated = snapshot.getImmediateDominatedIds(id);
    if (dominated == null || dominated.length == 0) {
      return null;
    }
    List<long[]> sizes = new ArrayList<>();
    for (int child : dominated) {
      sizes.add(new long[] {snapshot.getRetainedHeapSize(child), child});
    }
    sizes.sort((a, b) -> Long.compare(b[0], a[0]));
    List<Map<String, Object>> biggest = new ArrayList<>();
    for (int i = 0; i < Math.min(MAX_DOMINATED, sizes.size()); i++) {
      biggest.add(summary(snapshot, snapshot.getObject((int) sizes.get(i)[1]), total));
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("dominatedObjects", dominated.length);
    m.put("biggest", biggest);
    return m;
  }

  /**
   * The members of a group suspect that retain the most, as starting points for path_to_gc_roots. A group can have
   * millions of members, so only the biggest few are kept while scanning.
   */
  private static Map<String, Object> biggestInstances(ISnapshot snapshot, int[] ids) throws SnapshotException {
    PriorityQueue<long[]> biggest = new PriorityQueue<>(Comparator.comparingLong((long[] s) -> s[0]));
    for (int id : ids) {
      QueryCancellation.check();
      long retained = snapshot.getRetainedHeapSize(id);
      if (biggest.size() < BIGGEST_INSTANCES || retained > biggest.peek()[0]) {
        biggest.add(new long[] {retained, id});
        if (biggest.size() > BIGGEST_INSTANCES) {
          biggest.poll();
        }
      }
    }
    List<long[]> sorted = new ArrayList<>(biggest);
    sorted.sort(Comparator.comparingLong((long[] s) -> s[0]).reversed());
    List<Map<String, Object>> instances = new ArrayList<>();
    for (long[] s : sorted) {
      IObject object = snapshot.getObject((int) s[1]);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("label", label(object));
      m.put("address", AnalysisTools.hex(snapshot, object.getObjectId()));
      m.put("retainedBytes", s[0]);
      instances.add(m);
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("instances", instances);
    m.put("moreInstances", ids.length - instances.size());
    return m;
  }

  // ---------------------------------------------------------------- the collection at the accumulation point

  /**
   * Size, capacity and fill of the collection that holds the memory: the accumulation point itself, or, for an array,
   * the object that holds it, such as the HashMap behind a HashMap$Node[] table. Null if neither is a collection MAT
   * can read.
   */
  private static Map<String, Object> collectionFacts(ISnapshot snapshot, IObject accumulation, Path toPoint)
      throws SnapshotException {
    List<IObject> candidates = new ArrayList<>();
    if (accumulation != null) {
      // An array is usually the table inside a collection (HashMap$Node[] of a HashMap, Object[] of an ArrayList):
      // the collection that holds it has the size and the entries
      if (accumulation instanceof IArray && toPoint != null && toPoint.ids.length >= 2) {
        candidates.add(snapshot.getObject(toPoint.ids[toPoint.ids.length - 2]));
      }
      candidates.add(accumulation);
    }
    for (IObject candidate : candidates) {
      AbstractExtractedCollection<?, ?> collection = CollectionExtractionUtils.extractCollection(candidate);
      if (collection == null) {
        continue;
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("label", label(candidate));
      m.put("address", AnalysisTools.hex(snapshot, candidate.getObjectId()));
      m.put("className", AnalysisTools.className(candidate));
      m.put("isMap", collection instanceof ExtractedMap);
      m.put("size", collection.hasSize() ? collection.size() : null);
      m.put("capacity", collection.hasCapacity() ? collection.getCapacity() : null);
      Double fill = collection.hasFillRatio() ? collection.getFillRatio() : null;
      m.put("fillRatio", fill == null ? null : Math.round(fill * 100) / 100.0);
      Double collisions = collection instanceof ExtractedMap && ((ExtractedMap) collection).hasCollisionRatio()
          ? ((ExtractedMap) collection).getCollisionRatio() : null;
      m.put("collisionRatio", collisions == null ? null : Math.round(collisions * 100) / 100.0);
      m.put("sample", sample(collection));
      return m;
    }
    return null;
  }

  private static List<String> sample(AbstractExtractedCollection<?, ?> collection) throws SnapshotException {
    List<String> samples = new ArrayList<>();
    if (collection instanceof ExtractedMap) {
      for (Map.Entry<IObject, IObject> entry : (ExtractedMap) collection) {
        if (samples.size() == MAX_SAMPLES) {
          break;
        }
        samples.add(label(entry.getKey()) + " => " + label(entry.getValue()));
      }
    } else if (collection instanceof ExtractedCollection && collection.hasExtractableContents()) {
      for (IObject element : (ExtractedCollection) collection) {
        if (samples.size() == MAX_SAMPLES) {
          break;
        }
        samples.add(label(element));
      }
    }
    return samples;
  }

  // ---------------------------------------------------------------- threads

  private static boolean isThread(IObject object) {
    if (object instanceof IClass) {
      return false;
    }
    for (IClass c = object.getClazz(); c != null; c = c.getSuperClass()) {
      if ("java.lang.Thread".equals(c.getName())) {
        return true;
      }
    }
    return false;
  }

  /** The thread's stack, each frame with the local variables that retain the most. */
  private static Map<String, Object> thread(ISnapshot snapshot, IObject thread) throws SnapshotException {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("label", label(thread));
    m.put("address", AnalysisTools.hex(snapshot, thread.getObjectId()));
    long threadRetained = snapshot.getRetainedHeapSize(thread.getObjectId());
    m.put("retainedBytes", threadRetained);
    IThreadStack stack = snapshot.getThreadStack(thread.getObjectId());
    if (stack == null || stack.getStackFrames() == null || stack.getStackFrames().length == 0) {
      m.put("stackInDump", false);
      m.put("frames", new ArrayList<>());
      return m;
    }
    IStackFrame[] frames = stack.getStackFrames();
    m.put("stackInDump", true);
    m.put("localsMinimumShare", "1% of the thread's retained heap");
    int shown = Math.min(MAX_FRAMES, frames.length);
    List<Map<String, Object>> list = new ArrayList<>();
    for (int f = 0; f < shown; f++) {
      Map<String, Object> frame = new LinkedHashMap<>();
      frame.put("text", frames[f].getText());
      List<Map<String, Object>> locals = new ArrayList<>();
      int more = 0;
      int[] ids = frames[f].getLocalObjectsIds();
      if (ids != null && ids.length > 0) {
        // Each object once, and only those that hold a noticeable part of what the thread retains
        List<long[]> sizes = new ArrayList<>();
        for (int local : Arrays.stream(ids).distinct().toArray()) {
          long size = snapshot.getRetainedHeapSize(local);
          if (size * 100 >= threadRetained) {
            sizes.add(new long[] {size, local});
          }
        }
        sizes.sort((a, b) -> Long.compare(b[0], a[0]));
        for (int i = 0; i < Math.min(LOCALS_PER_FRAME, sizes.size()); i++) {
          IObject object = snapshot.getObject((int) sizes.get(i)[1]);
          Map<String, Object> local = new LinkedHashMap<>();
          local.put("label", label(object));
          local.put("address", AnalysisTools.hex(snapshot, object.getObjectId()));
          local.put("retainedBytes", sizes.get(i)[0]);
          locals.add(local);
        }
        more = Math.max(0, sizes.size() - LOCALS_PER_FRAME);
      }
      frame.put("locals", locals);
      frame.put("moreLocals", more);
      list.add(frame);
    }
    m.put("frames", list);
    m.put("moreFrames", frames.length - shown);
    return m;
  }

  // ---------------------------------------------------------------- helpers

  private static String classLoader(ISnapshot snapshot, IObject object) throws SnapshotException {
    IClass clazz = object instanceof IClass ? (IClass) object : object.getClazz();
    int loader = clazz.getClassLoaderId();
    return loader < 0 ? "(unknown)" : snapshot.getObject(loader).getDisplayName();
  }

  /** The object's label, cut short: the label of a byte[] or char[] holds its contents. */
  private static String label(IObject object) {
    String name = object.getDisplayName();
    return name.length() > MAX_LABEL ? name.substring(0, MAX_LABEL) + "..." : name;
  }

  /** Percent with two decimals, as a number. */
  private static double percent(long part, long whole) {
    return whole == 0 ? 0 : Math.round(10000.0 * part / whole) / 100.0;
  }

  // ---------------------------------------------------------------- text

  @SuppressWarnings("unchecked")
  static String toText(Map<String, Object> model) {
    List<Map<String, Object>> suspects = (List<Map<String, Object>>) model.get("suspects");
    if (suspects.isEmpty()) {
      return (String) model.get("message");
    }
    int found = (Integer) model.get("suspectsFound");
    StringBuilder sb = new StringBuilder();
    sb.append(found).append(found == 1 ? " suspect" : " suspects").append(" retaining more than ")
        .append(model.get("thresholdPercent")).append("% of the heap (").append(model.get("usedHeapBytes"))
        .append(" bytes):\n");
    for (Map<String, Object> s : suspects) {
      sb.append("\n## Suspect ").append(s.get("rank")).append(": ").append(s.get("label")).append('\n');
      sb.append("retains ").append(s.get("retainedBytes")).append(" bytes (").append(s.get("retainedPercent"))
          .append("% of the heap)\n");
      sb.append("class loader: ").append(s.get("classLoader")).append('\n');
      Map<String, Object> point = (Map<String, Object>) s.get("accumulationPoint");
      if (point != null) {
        sb.append("accumulation point (where the memory piles up): ").append(point.get("label")).append(", retains ")
            .append(point.get("retainedBytes")).append(" bytes\n");
      } else if ("group".equals(s.get("kind"))) {
        sb.append("accumulation point: none, no single object holds the group; each instance is kept alive on its "
            + "own\n");
      }
      appendClasses(sb, (Map<String, Object>) s.get("retainedByClass"));
      appendDominates(sb, (Map<String, Object>) s.get("dominates"));
      Map<String, Object> biggest = (Map<String, Object>) s.get("biggestInstances");
      if (biggest != null) {
        sb.append("biggest instances:\n");
        for (Map<String, Object> instance : (List<Map<String, Object>>) biggest.get("instances")) {
          sb.append("  ").append(instance.get("label")).append(", retains ").append(instance.get("retainedBytes"))
              .append(" bytes\n");
        }
        long more = ((Number) biggest.get("moreInstances")).longValue();
        if (more > 0) {
          sb.append("  ... ").append(more).append(" more\n");
        }
      }
      appendPath(sb, "path from a GC root:", (List<Map<String, Object>>) s.get("pathFromGcRoot"));
      appendPath(sb, "path from a GC root to the accumulation point (who holds the memory):",
          (List<Map<String, Object>>) s.get("pathToAccumulationPoint"));
      appendCollection(sb, (Map<String, Object>) s.get("collection"));
      appendThread(sb, (Map<String, Object>) s.get("thread"));
      for (String note : (List<String>) s.get("notes")) {
        sb.append("note: ").append(note).append('\n');
      }
    }
    sb.append("\nNext: retained_set or collection_contents on an accumulation point, path_to_gc_roots on a suspect. "
        + "format json gives these numbers as data.");
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static void appendClasses(StringBuilder sb, Map<String, Object> retained) {
    sb.append("what the retained heap is made of (").append(retained.get("totalObjects")).append(" objects, by "
        + "shallow heap):\n");
    for (Map<String, Object> c : (List<Map<String, Object>>) retained.get("classes")) {
      sb.append("  ").append(c.get("className")).append(": ").append(c.get("objects")).append(" objects, ")
          .append(c.get("shallowBytes")).append(" bytes (").append(c.get("sharePercent")).append("%)\n");
    }
    Map<String, Object> other = (Map<String, Object>) retained.get("other");
    if (other != null) {
      sb.append("  ... ").append(other.get("classes")).append(" more classes: ").append(other.get("objects"))
          .append(" objects, ").append(other.get("shallowBytes")).append(" bytes\n");
    }
  }

  @SuppressWarnings("unchecked")
  private static void appendDominates(StringBuilder sb, Map<String, Object> dominates) {
    if (dominates == null) {
      return;
    }
    sb.append("dominates ").append(dominates.get("dominatedObjects")).append(" objects, the biggest:\n");
    for (Map<String, Object> o : (List<Map<String, Object>>) dominates.get("biggest")) {
      sb.append("  ").append(o.get("label")).append(", retains ").append(o.get("retainedBytes")).append(" bytes (")
          .append(o.get("retainedPercent")).append("% of the heap)\n");
    }
  }

  private static void appendPath(StringBuilder sb, String title, List<Map<String, Object>> nodes) {
    if (nodes == null) {
      return;
    }
    sb.append(title).append('\n');
    int depth = 0;
    for (Map<String, Object> node : nodes) {
      for (int i = 0; i < depth + 1; i++) {
        sb.append("  ");
      }
      if (node.containsKey("omittedNodes")) {
        sb.append("... ").append(node.get("omittedNodes")).append(" more nodes\n");
        continue;
      }
      if (node.get("viaReference") != null) {
        sb.append(node.get("viaReference")).append(": ");
      }
      sb.append(node.get("label"));
      if (node.get("gcRootTypes") != null) {
        sb.append("  [GC root: ").append(node.get("gcRootTypes")).append(']');
      }
      sb.append(", retains ").append(node.get("retainedBytes")).append(" bytes\n");
      depth++;
    }
  }

  @SuppressWarnings("unchecked")
  private static void appendCollection(StringBuilder sb, Map<String, Object> c) {
    if (c == null) {
      return;
    }
    sb.append("collection at the accumulation point: ").append(c.get("label"));
    for (String[] fact : new String[][] {{"size", "size"}, {"capacity", "capacity"}, {"fillRatio", "fill ratio"},
        {"collisionRatio", "collision ratio"}}) {
      if (c.get(fact[0]) != null) {
        sb.append(", ").append(fact[1]).append(' ').append(c.get(fact[0]));
      }
    }
    sb.append('\n');
    for (String sample : (List<String>) c.get("sample")) {
      sb.append("  e.g. ").append(sample).append('\n');
    }
  }

  @SuppressWarnings("unchecked")
  private static void appendThread(StringBuilder sb, Map<String, Object> t) {
    if (t == null) {
      return;
    }
    if (Boolean.FALSE.equals(t.get("stackInDump"))) {
      sb.append("thread stack: not in the dump\n");
      return;
    }
    sb.append("thread stack of ").append(t.get("label")).append(", with the local variables that retain at least 1% "
        + "of the thread's ").append(t.get("retainedBytes")).append(" bytes:\n");
    for (Map<String, Object> frame : (List<Map<String, Object>>) t.get("frames")) {
      sb.append("  ").append(frame.get("text")).append('\n');
      for (Map<String, Object> local : (List<Map<String, Object>>) frame.get("locals")) {
        sb.append("      local ").append(local.get("label")).append(", retains ").append(local.get("retainedBytes"))
            .append(" bytes\n");
      }
      long more = ((Number) frame.get("moreLocals")).longValue();
      if (more > 0) {
        sb.append("      ... ").append(more).append(" more locals\n");
      }
    }
    long moreFrames = ((Number) t.get("moreFrames")).longValue();
    if (moreFrames > 0) {
      sb.append("  ... ").append(moreFrames).append(" more frames\n");
    }
  }
}
