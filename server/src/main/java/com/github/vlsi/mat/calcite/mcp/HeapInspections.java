package com.github.vlsi.mat.calcite.mcp;

import com.github.vlsi.mat.calcite.mcp.McpServer.ToolException;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.query.IResult;
import org.eclipse.mat.query.IResultTable;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.Field;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IInstance;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IObjectArray;
import org.eclipse.mat.snapshot.model.IPrimitiveArray;
import org.eclipse.mat.snapshot.model.ObjectReference;
import org.eclipse.mat.snapshot.query.SnapshotQuery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Inspections for memory waste and for possible leaks, in the manner of a profiler's automatic memory inspections:
 * thread locals that keep values alive, arrays that are empty, sparse or constant, fields that are null in most
 * objects, and a summary that runs them all and ranks what they found. Each reports estimates in bytes, as upper
 * bounds on what a change could save, with an example address to look at.
 */
final class HeapInspections {
  private static final int MAX_LABEL = 120;

  /** Arrays longer than this are judged on their first {@link #SAMPLE_ELEMENTS} elements, to bound the time. */
  private static final int LONG_ARRAY = 1_000_000;
  private static final int SAMPLE_ELEMENTS = 65_536;
  /** Instances of one class read to find its null fields; more are sampled evenly. */
  private static final int INSTANCES_PER_CLASS = 20_000;
  /** Instances read in all by one null-fields scan. */
  private static final int INSTANCES_IN_ALL = 2_000_000;

  private HeapInspections() {
  }

  // ---------------------------------------------------------------- thread locals

  /** One value held by a thread through a thread local. */
  private static final class ThreadLocalValue {
    final String thread;
    final String key;
    final String value;
    final String address;
    final long retained;
    final boolean stale;

    ThreadLocalValue(String thread, String key, String value, String address, long retained, boolean stale) {
      this.thread = thread;
      this.key = key;
      this.value = value;
      this.address = address;
      this.retained = retained;
      this.stale = stale;
    }
  }

  private static final class ThreadStats {
    final String thread;
    long entries;
    long stale;
    long bytes;
    long staleBytes;

    ThreadStats(String thread) {
      this.thread = thread;
    }
  }

  /** What the scan of the thread locals found. */
  static final class ThreadLocalsScan {
    long threads;
    long threadsWithValues;
    long entries;
    long stale;
    long bytes;
    long staleBytes;
    private final List<ThreadStats> perThread = new ArrayList<>();
    private final List<ThreadLocalValue> values = new ArrayList<>();
  }

  static ThreadLocalsScan scanThreadLocals(ISnapshot snapshot, boolean onlyStale) throws Exception {
    ThreadLocalsScan scan = new ThreadLocalsScan();
    Collection<IClass> classes = snapshot.getClassesByName("java.lang.Thread", true);
    if (classes == null) {
      return scan;
    }
    for (IClass clazz : classes) {
      for (int id : clazz.getObjectIds()) {
        AnalysisTools.checkCancelled();
        IObject object = snapshot.getObject(id);
        if (!(object instanceof IInstance)) {
          continue;
        }
        scan.threads++;
        IInstance thread = (IInstance) object;
        ThreadStats stats = new ThreadStats(label(thread));
        for (String field : new String[] {"threadLocals", "inheritableThreadLocals"}) {
          IObject map = referenced(thread.getField(field));
          IObject table = map instanceof IInstance ? referenced(((IInstance) map).getField("table")) : null;
          if (!(table instanceof IObjectArray)) {
            continue;
          }
          for (long address : ((IObjectArray) table).getReferenceArray()) {
            if (address == 0) {
              continue;
            }
            IObject entry = snapshot.getObject(snapshot.mapAddressToId(address));
            if (!(entry instanceof IInstance)) {
              continue;
            }
            // ThreadLocalMap.Entry is a WeakReference to the ThreadLocal: when that is collected, the entry stays
            IObject key = referenced(((IInstance) entry).getField("referent"));
            IObject value = referenced(((IInstance) entry).getField("value"));
            boolean stale = key == null;
            if (onlyStale && !stale) {
              continue;
            }
            long retained = value == null ? 0 : snapshot.getRetainedHeapSize(value.getObjectId());
            stats.entries++;
            stats.bytes += retained;
            if (stale) {
              stats.stale++;
              stats.staleBytes += retained;
            }
            scan.values.add(new ThreadLocalValue(stats.thread, key == null ? "(collected)" : label(key),
                value == null ? "null" : label(value), value == null ? "" : AnalysisTools.hex(snapshot,
                    value.getObjectId()), retained, stale));
          }
        }
        if (stats.entries > 0) {
          scan.threadsWithValues++;
          scan.entries += stats.entries;
          scan.stale += stats.stale;
          scan.bytes += stats.bytes;
          scan.staleBytes += stats.staleBytes;
          scan.perThread.add(stats);
        }
      }
    }
    scan.perThread.sort(Comparator.comparingLong((ThreadStats t) -> t.bytes).reversed());
    scan.values.sort(Comparator.comparingLong((ThreadLocalValue v) -> v.retained).reversed());
    return scan;
  }

  static String threadLocals(ISnapshot snapshot, int maxRows, boolean onlyStale) throws Exception {
    ThreadLocalsScan scan = scanThreadLocals(snapshot, onlyStale);
    long heap = snapshot.getSnapshotInfo().getUsedHeapSize();
    if (scan.entries == 0) {
      return onlyStale ? "No thread holds the value of a thread local that has been garbage collected (" + scan.threads
          + " threads checked)." : "No thread holds thread-local values (" + scan.threads + " threads checked).";
    }
    StringBuilder sb = new StringBuilder();
    sb.append(scan.threadsWithValues).append(" of ").append(scan.threads)
        .append(scan.threadsWithValues == 1 ? " threads holds " : " threads hold ").append(scan.entries)
        .append(scan.entries == 1 ? " thread-local value" : " thread-local values").append("; ")
        .append(scan.stale).append(" of them stale (the ThreadLocal was garbage collected, the thread still holds "
            + "the value). The values retain ").append(scan.bytes).append(" bytes (")
        .append(AnalysisTools.percent(scan.bytes, heap)).append(" of the heap), ").append(scan.staleBytes)
        .append(" of them through stale entries.\n\n");
    sb.append("| thread | values | stale | retained bytes | stale bytes |\n|---|---|---|---|---|\n");
    for (int i = 0; i < Math.min(maxRows, scan.perThread.size()); i++) {
      ThreadStats t = scan.perThread.get(i);
      sb.append("| ").append(cell(t.thread)).append(" | ").append(t.entries).append(" | ").append(t.stale)
          .append(" | ").append(t.bytes).append(" | ").append(t.staleBytes).append(" |\n");
    }
    if (scan.perThread.size() > maxRows) {
      sb.append("\n").append(maxRows).append(" of ").append(scan.perThread.size()).append(" threads shown (raise "
          + "max_rows)\n");
    }
    sb.append("\n## Biggest values\n\n| thread | thread local | value | retained bytes | stale |\n|---|---|---|---|---|\n");
    for (int i = 0; i < Math.min(maxRows, scan.values.size()); i++) {
      ThreadLocalValue v = scan.values.get(i);
      sb.append("| ").append(cell(v.thread)).append(" | ").append(cell(v.key)).append(" | ").append(cell(v.value))
          .append(" | ").append(v.retained).append(" | ").append(v.stale ? "yes" : "no").append(" |\n");
    }
    sb.append("\nA stale value stays reachable until its thread next touches its thread locals, which in a thread "
        + "pool may be never: it is how a class loader or a request object leaks. A value is in use when its "
        + "ThreadLocal is not collected. Next: threads with a thread's address shows its stack; object_info or "
        + "path_to_gc_roots on a value's address shows what holds it.");
    return sb.toString();
  }

  /** The object a field refers to, or null for a null reference or a primitive. */
  private static IObject referenced(Field field) throws SnapshotException {
    if (field == null || !(field.getValue() instanceof ObjectReference)) {
      return null;
    }
    ObjectReference reference = (ObjectReference) field.getValue();
    return reference.getObjectAddress() == 0 ? null : reference.getObject();
  }

  // ---------------------------------------------------------------- arrays

  /** The array waste found in one array class. */
  private static final class ArrayStats {
    final String className;
    long arrays;
    long zeroLength;
    long zeroLengthBytesEach;
    int zeroLengthExample = -1;
    long sparse;
    long sparseSlots;
    long sparseWastedSlots;
    long sparseWaste;
    int sparseExample = -1;
    long sparseExampleWaste = -1;
    long constant;
    long constantBytes;
    int constantExample = -1;
    long constantExampleBytes = -1;

    ArrayStats(String className) {
      this.className = className;
    }

    long zeroLengthWaste() {
      return zeroLength > 1 ? (zeroLength - 1) * zeroLengthBytesEach : 0;
    }
  }

  /** What the scan of the arrays found. */
  static final class ArrayScan {
    long arrays;
    long arrayBytes;
    long zeroLength;
    long zeroLengthWaste;
    long sparse;
    long sparseWaste;
    long constant;
    long constantWaste;
    boolean sampledLongArrays;
    int minLength;
    int minPercent;
    private final List<ArrayStats> classes = new ArrayList<>();

    long waste() {
      return zeroLengthWaste + sparseWaste + constantWaste;
    }
  }

  static ArrayScan scanArrays(ISnapshot snapshot, String classRegex, int minLength, int minPercent)
      throws Exception {
    Pattern filter = classRegex == null || classRegex.trim().isEmpty() ? null : Pattern.compile(classRegex.trim());
    int referenceSize = AnalysisTools.referenceSize(snapshot);
    ArrayScan scan = new ArrayScan();
    scan.minLength = minLength;
    scan.minPercent = minPercent;
    for (IClass clazz : snapshot.getClasses()) {
      if (!clazz.isArrayType() || clazz.getNumberOfObjects() == 0
          || filter != null && !filter.matcher(clazz.getName()).matches()) {
        continue;
      }
      ArrayStats stats = new ArrayStats(clazz.getName());
      for (int id : clazz.getObjectIds()) {
        AnalysisTools.checkCancelled();
        IObject array = snapshot.getObject(id);
        long shallow = array.getUsedHeapSize();
        int length;
        int elementSize;
        if (array instanceof IPrimitiveArray) {
          length = ((IPrimitiveArray) array).getLength();
          elementSize = primitiveSize(((IPrimitiveArray) array).getType());
        } else if (array instanceof IObjectArray) {
          length = ((IObjectArray) array).getLength();
          elementSize = referenceSize;
        } else {
          continue;
        }
        stats.arrays++;
        scan.arrays++;
        scan.arrayBytes += shallow;
        if (length == 0) {
          stats.zeroLength++;
          stats.zeroLengthBytesEach = shallow;
          stats.zeroLengthExample = id;
          continue;
        }
        if (length < minLength) {
          continue;
        }
        boolean sampled = length > LONG_ARRAY;
        int checked = sampled ? SAMPLE_ELEMENTS : length;
        scan.sampledLongArrays |= sampled;
        long[] counts = array instanceof IPrimitiveArray
            ? scanPrimitive(((IPrimitiveArray) array).getValueArray(0, checked))
            : scanReferences(((IObjectArray) array).getReferenceArray(0, checked));
        long zeros = counts[0];
        boolean same = counts[1] == 1;
        if (same && !sampled) {
          stats.constant++;
          stats.constantBytes += shallow;
          if (shallow > stats.constantExampleBytes) {
            stats.constantExampleBytes = shallow;
            stats.constantExample = id;
          }
        } else if (zeros * 100 >= (long) minPercent * checked) {
          long wastedSlots = sampled ? Math.round((double) zeros / checked * length) : zeros;
          long waste = wastedSlots * elementSize;
          stats.sparse++;
          stats.sparseSlots += length;
          stats.sparseWastedSlots += wastedSlots;
          stats.sparseWaste += waste;
          if (waste > stats.sparseExampleWaste) {
            stats.sparseExampleWaste = waste;
            stats.sparseExample = id;
          }
        }
      }
      if (stats.zeroLength > 0 || stats.sparse > 0 || stats.constant > 0) {
        scan.classes.add(stats);
      }
      scan.zeroLength += stats.zeroLength;
      scan.zeroLengthWaste += stats.zeroLengthWaste();
      scan.sparse += stats.sparse;
      scan.sparseWaste += stats.sparseWaste;
      scan.constant += stats.constant;
      scan.constantWaste += stats.constantBytes;
    }
    return scan;
  }

  /** Bytes per element of a primitive array type, as IObject.Type numbers them. */
  private static int primitiveSize(int type) {
    switch (type) {
      case IObject.Type.BOOLEAN:
      case IObject.Type.BYTE:
        return 1;
      case IObject.Type.CHAR:
      case IObject.Type.SHORT:
        return 2;
      case IObject.Type.FLOAT:
      case IObject.Type.INT:
        return 4;
      default:
        return 8; // long, double
    }
  }

  /** {number of zero elements, 1 if all elements are equal else 0}. */
  private static long[] scanPrimitive(Object values) {
    long zeros = 0;
    boolean same = true;
    if (values instanceof byte[]) {
      byte[] a = (byte[]) values;
      for (byte v : a) {
        zeros += v == 0 ? 1 : 0;
        same &= v == a[0];
      }
    } else if (values instanceof char[]) {
      char[] a = (char[]) values;
      for (char v : a) {
        zeros += v == 0 ? 1 : 0;
        same &= v == a[0];
      }
    } else if (values instanceof short[]) {
      short[] a = (short[]) values;
      for (short v : a) {
        zeros += v == 0 ? 1 : 0;
        same &= v == a[0];
      }
    } else if (values instanceof int[]) {
      int[] a = (int[]) values;
      for (int v : a) {
        zeros += v == 0 ? 1 : 0;
        same &= v == a[0];
      }
    } else if (values instanceof long[]) {
      long[] a = (long[]) values;
      for (long v : a) {
        zeros += v == 0 ? 1 : 0;
        same &= v == a[0];
      }
    } else if (values instanceof float[]) {
      float[] a = (float[]) values;
      for (float v : a) {
        zeros += v == 0 ? 1 : 0;
        same &= Float.floatToIntBits(v) == Float.floatToIntBits(a[0]);
      }
    } else if (values instanceof double[]) {
      double[] a = (double[]) values;
      for (double v : a) {
        zeros += v == 0 ? 1 : 0;
        same &= Double.doubleToLongBits(v) == Double.doubleToLongBits(a[0]);
      }
    } else if (values instanceof boolean[]) {
      boolean[] a = (boolean[]) values;
      for (boolean v : a) {
        zeros += v ? 0 : 1;
        same &= v == a[0];
      }
    }
    return new long[] {zeros, same ? 1 : 0};
  }

  /** {number of null references, 1 if all references are equal else 0}. */
  private static long[] scanReferences(long[] addresses) {
    long nulls = 0;
    boolean same = true;
    for (long a : addresses) {
      nulls += a == 0 ? 1 : 0;
      same &= a == addresses[0];
    }
    return new long[] {nulls, same ? 1 : 0};
  }

  static String arrayWaste(ISnapshot snapshot, String classRegex, int minLength, int minPercent, int maxRows)
      throws Exception {
    ArrayScan scan = scanArrays(snapshot, classRegex, minLength, minPercent);
    long heap = snapshot.getSnapshotInfo().getUsedHeapSize();
    StringBuilder sb = new StringBuilder();
    sb.append(scan.arrays).append(" arrays (").append(scan.arrayBytes).append(" bytes shallow, ")
        .append(AnalysisTools.percent(scan.arrayBytes, heap)).append(" of the heap). Estimated waste ")
        .append(scan.waste()).append(" bytes (").append(AnalysisTools.percent(scan.waste(), heap))
        .append(" of the heap):\n")
        .append("- zero-length arrays: ").append(scan.zeroLength).append(", ").append(scan.zeroLengthWaste)
        .append(" bytes (all but one per class could be one shared instance)\n")
        .append("- sparse arrays, at least ").append(scan.minPercent).append("% null or zero and ")
        .append(scan.minLength).append(" elements: ").append(scan.sparse).append(", ").append(scan.sparseWaste)
        .append(" bytes (the empty slots)\n")
        .append("- arrays whose elements are all the same: ").append(scan.constant).append(", ")
        .append(scan.constantWaste).append(" bytes (a value and a length would do)\n");
    if (scan.sampledLongArrays) {
      sb.append("Arrays of more than ").append(LONG_ARRAY).append(" elements are judged on their first ")
          .append(SAMPLE_ELEMENTS).append(", and never counted as all the same.\n");
    }
    appendZeroLength(sb, scan, maxRows);
    appendSparse(sb, snapshot, scan, maxRows);
    appendConstant(sb, snapshot, scan, maxRows);
    sb.append("\nNext: object_info or path_to_gc_roots on an example address, collection_stats with kind array_sizes "
        + "for the sizes of the arrays of a class.");
    return sb.toString();
  }

  private static void appendZeroLength(StringBuilder sb, ArrayScan scan, int maxRows) {
    List<ArrayStats> rows = new ArrayList<>();
    for (ArrayStats s : scan.classes) {
      if (s.zeroLength > 1) {
        rows.add(s);
      }
    }
    rows.sort(Comparator.comparingLong(ArrayStats::zeroLengthWaste).reversed());
    if (rows.isEmpty()) {
      return;
    }
    sb.append("\n## Zero-length arrays\n\n| class | arrays | bytes each | waste (bytes) |\n|---|---|---|---|\n");
    for (int i = 0; i < Math.min(maxRows, rows.size()); i++) {
      ArrayStats s = rows.get(i);
      sb.append("| ").append(s.className).append(" | ").append(s.zeroLength).append(" | ")
          .append(s.zeroLengthBytesEach).append(" | ").append(s.zeroLengthWaste()).append(" |\n");
    }
  }

  private static void appendSparse(StringBuilder sb, ISnapshot snapshot, ArrayScan scan, int maxRows)
      throws SnapshotException {
    List<ArrayStats> rows = new ArrayList<>();
    for (ArrayStats s : scan.classes) {
      if (s.sparse > 0) {
        rows.add(s);
      }
    }
    rows.sort(Comparator.comparingLong((ArrayStats s) -> s.sparseWaste).reversed());
    if (rows.isEmpty()) {
      return;
    }
    sb.append("\n## Sparse arrays\n\n| class | arrays | slots | empty slots | waste (bytes) | worst example |\n"
        + "|---|---|---|---|---|---|\n");
    for (int i = 0; i < Math.min(maxRows, rows.size()); i++) {
      ArrayStats s = rows.get(i);
      sb.append("| ").append(s.className).append(" | ").append(s.sparse).append(" | ").append(s.sparseSlots)
          .append(" | ").append(s.sparseWastedSlots).append(" | ").append(s.sparseWaste).append(" | ")
          .append(AnalysisTools.hex(snapshot, s.sparseExample)).append(" |\n");
    }
  }

  private static void appendConstant(StringBuilder sb, ISnapshot snapshot, ArrayScan scan, int maxRows)
      throws SnapshotException {
    List<ArrayStats> rows = new ArrayList<>();
    for (ArrayStats s : scan.classes) {
      if (s.constant > 0) {
        rows.add(s);
      }
    }
    rows.sort(Comparator.comparingLong((ArrayStats s) -> s.constantBytes).reversed());
    if (rows.isEmpty()) {
      return;
    }
    sb.append("\n## Arrays whose elements are all the same\n\n| class | arrays | bytes | biggest example |\n"
        + "|---|---|---|---|\n");
    for (int i = 0; i < Math.min(maxRows, rows.size()); i++) {
      ArrayStats s = rows.get(i);
      sb.append("| ").append(s.className).append(" | ").append(s.constant).append(" | ").append(s.constantBytes)
          .append(" | ").append(AnalysisTools.hex(snapshot, s.constantExample)).append(" |\n");
    }
  }

  // ---------------------------------------------------------------- null fields

  /** The null counts of one field of one class. */
  private static final class NullField {
    final String className;
    final String field;
    final long instances;
    final long sampled;
    final long nulls;
    final long waste;

    NullField(String className, String field, long instances, long sampled, long nulls, int referenceSize) {
      this.className = className;
      this.field = field;
      this.instances = instances;
      this.sampled = sampled;
      this.nulls = nulls;
      this.waste = Math.round((double) nulls / sampled * instances) * referenceSize;
    }

    double nullPercent() {
      return 100.0 * nulls / sampled;
    }
  }

  /** What the scan of the fields found. */
  static final class NullFieldsScan {
    long classes;
    long instancesRead;
    boolean sampled;
    boolean stoppedEarly;
    long waste;
    private final List<NullField> fields = new ArrayList<>();
  }

  static NullFieldsScan scanNullFields(ISnapshot snapshot, String classRegex, int minInstances, int minNullPercent)
      throws Exception {
    Pattern filter = classRegex == null || classRegex.trim().isEmpty() ? null : Pattern.compile(classRegex.trim());
    int referenceSize = AnalysisTools.referenceSize(snapshot);
    List<IClass> candidates = new ArrayList<>();
    for (IClass clazz : snapshot.getClasses()) {
      // The objects of java.lang.Class are the JVM's mirrors of classes: their fields are not for anyone to change
      if (!clazz.isArrayType() && !"java.lang.Class".equals(clazz.getName())
          && clazz.getNumberOfObjects() >= minInstances
          && (filter == null || filter.matcher(clazz.getName()).matches())) {
        candidates.add(clazz);
      }
    }
    candidates.sort(Comparator.comparingInt(IClass::getNumberOfObjects).reversed());
    NullFieldsScan scan = new NullFieldsScan();
    for (IClass clazz : candidates) {
      if (scan.instancesRead >= INSTANCES_IN_ALL) {
        scan.stoppedEarly = true;
        break;
      }
      int[] ids = clazz.getObjectIds();
      int step = Math.max(1, ids.length / INSTANCES_PER_CLASS);
      Map<String, long[]> counts = new LinkedHashMap<>(); // field -> {objects with the field, nulls}
      long sampled = 0;
      for (int i = 0; i < ids.length; i += step) {
        AnalysisTools.checkCancelled();
        IObject object = snapshot.getObject(ids[i]);
        if (!(object instanceof IInstance)) {
          continue;
        }
        sampled++;
        for (Field field : ((IInstance) object).getFields()) {
          if (field.getType() != IObject.Type.OBJECT) {
            continue;
          }
          long[] c = counts.computeIfAbsent(field.getName(), k -> new long[2]);
          c[0]++;
          c[1] += isNull(field) ? 1 : 0;
        }
      }
      scan.instancesRead += sampled;
      scan.classes++;
      scan.sampled |= step > 1;
      for (Map.Entry<String, long[]> e : counts.entrySet()) {
        long[] c = e.getValue();
        if (c[0] > 0 && c[1] * 100 >= (long) minNullPercent * c[0]) {
          NullField f = new NullField(clazz.getName(), e.getKey(), ids.length, c[0], c[1], referenceSize);
          scan.fields.add(f);
          scan.waste += f.waste;
        }
      }
    }
    scan.fields.sort(Comparator.comparingLong((NullField f) -> f.waste).reversed());
    return scan;
  }

  private static boolean isNull(Field field) {
    Object value = field.getValue();
    return value == null || value instanceof ObjectReference && ((ObjectReference) value).getObjectAddress() == 0;
  }

  static String nullFields(ISnapshot snapshot, String classRegex, int minInstances, int minNullPercent, int maxRows)
      throws Exception {
    NullFieldsScan scan = scanNullFields(snapshot, classRegex, minInstances, minNullPercent);
    long heap = snapshot.getSnapshotInfo().getUsedHeapSize();
    StringBuilder sb = new StringBuilder();
    sb.append(scan.fields.size()).append(" reference fields are null in at least ").append(minNullPercent)
        .append("% of the instances of classes with at least ").append(minInstances).append(" instances (")
        .append(scan.classes).append(" classes, ").append(scan.instancesRead).append(" instances read). Estimated "
            + "waste ").append(scan.waste).append(" bytes (").append(AnalysisTools.percent(scan.waste, heap))
        .append(" of the heap) = null fields x the size of a reference.\n");
    if (scan.sampled) {
      sb.append("Classes with more than ").append(INSTANCES_PER_CLASS).append(" instances are judged on an even "
          + "sample of ").append(INSTANCES_PER_CLASS).append(" (marked ~).\n");
    }
    if (scan.stoppedEarly) {
      sb.append("The scan stopped after ").append(INSTANCES_IN_ALL).append(" instances; classes with fewer "
          + "instances were not read. Narrow it with class_regex.\n");
    }
    if (scan.fields.isEmpty()) {
      return sb.append("Lower min_null_percent or min_instances to see more.").toString();
    }
    sb.append("\n| class | field | instances | null | null % | est. waste (bytes) |\n|---|---|---|---|---|---|\n");
    for (int i = 0; i < Math.min(maxRows, scan.fields.size()); i++) {
      NullField f = scan.fields.get(i);
      boolean approx = f.sampled < f.instances;
      sb.append("| ").append(f.className).append(" | ").append(f.field).append(" | ").append(f.instances)
          .append(" | ").append(approx ? "~" : "").append(Math.round((double) f.nulls / f.sampled * f.instances))
          .append(" | ").append(String.format(Locale.ROOT, "%.1f", f.nullPercent())).append(" | ").append(f.waste)
          .append(" |\n");
    }
    if (scan.fields.size() > maxRows) {
      sb.append("\n").append(maxRows).append(" of ").append(scan.fields.size()).append(" fields shown (raise "
          + "max_rows)\n");
    }
    sb.append("\nA field that is null in most objects costs a reference in every one of them. Moving it into a "
        + "subclass, or into a holder object created on first use, saves that. The estimate is an upper bound. "
        + "Next: sql_query on the class to see the objects, or histogram for its total size.");
    return sb.toString();
  }

  // ---------------------------------------------------------------- the summary

  /** The inspections the summary can run, in the order it runs them. */
  private static final List<String> INSPECTIONS = Arrays.asList("duplicate_strings", "wasteful_collections",
      "array_waste", "null_fields", "thread_locals", "duplicate_classes");

  /** One line of the summary. */
  private static final class Finding {
    final String name;
    final String found;
    final long waste;
    final String tool;
    final boolean leak;

    Finding(String name, String found, long waste, String tool, boolean leak) {
      this.name = name;
      this.found = found;
      this.waste = waste;
      this.tool = tool;
      this.leak = leak;
    }
  }

  static String memoryInspections(ISnapshot snapshot, String only, Cancellation cancellation) throws Exception {
    List<String> selected = new ArrayList<>();
    if (only == null || only.trim().isEmpty()) {
      selected.addAll(INSPECTIONS);
    } else {
      for (String name : only.trim().toLowerCase(Locale.ROOT).split("[\\s,]+")) {
        if (!INSPECTIONS.contains(name)) {
          throw new ToolException("inspections must be a list of " + String.join(", ", INSPECTIONS) + ", got " + name);
        }
        selected.add(name);
      }
    }
    long heap = snapshot.getSnapshotInfo().getUsedHeapSize();
    List<Finding> findings = new ArrayList<>();
    List<String> failures = new ArrayList<>();
    for (String name : INSPECTIONS) {
      if (!selected.contains(name)) {
        continue;
      }
      if (cancellation.isCancelled()) {
        break;
      }
      try {
        switch (name) {
          case "duplicate_strings": {
            AnalysisTools.DuplicateStringsScan s = AnalysisTools.scanDuplicateStrings(snapshot, 2);
            findings.add(new Finding("Duplicate strings", s.duplicates.size() + " values in " + s.duplicatedStrings
                + " strings", s.wastedTotal, name, false));
            break;
          }
          case "wasteful_collections": {
            AnalysisTools.WastefulScan s = AnalysisTools.scanWastefulCollections(snapshot);
            findings.add(new Finding("Empty and under-filled collections", s.emptyCollections + " empty of "
                + s.collections, s.totalWaste, name, false));
            break;
          }
          case "array_waste": {
            ArrayScan s = scanArrays(snapshot, null, 8, 50);
            findings.add(new Finding("Zero-length arrays", s.zeroLength + " arrays", s.zeroLengthWaste, name, false));
            findings.add(new Finding("Sparse arrays", s.sparse + " arrays", s.sparseWaste, name, false));
            findings.add(new Finding("Arrays with all the same element", s.constant + " arrays", s.constantWaste,
                name, false));
            break;
          }
          case "null_fields": {
            NullFieldsScan s = scanNullFields(snapshot, null, 100, 80);
            findings.add(new Finding("Fields that are null in most objects", s.fields.size() + " fields", s.waste,
                name, false));
            break;
          }
          case "thread_locals": {
            ThreadLocalsScan s = scanThreadLocals(snapshot, false);
            findings.add(new Finding("Stale thread-local values", s.stale + " of " + s.entries + " values in "
                + s.threadsWithValues + " threads", s.staleBytes, name, true));
            break;
          }
          default: {
            IResult result = SnapshotQuery.lookup("duplicate_classes", snapshot).execute(cancellation.listener());
            long rows = result instanceof IResultTable ? ((IResultTable) result).getRowCount() : 0;
            findings.add(new Finding("Classes defined by several class loaders", rows + " classes", 0, name, true));
            break;
          }
        }
      } catch (Exception e) {
        if (cancellation.isCancelled()) {
          throw e;
        }
        failures.add(name + ": " + e.getMessage());
      }
    }
    return summary(snapshot, heap, findings, failures);
  }

  private static String summary(ISnapshot snapshot, long heap, List<Finding> findings, List<String> failures) {
    List<Finding> waste = new ArrayList<>();
    List<Finding> leaks = new ArrayList<>();
    for (Finding f : findings) {
      if (f.leak) {
        leaks.add(f);
      } else {
        waste.add(f);
      }
    }
    waste.sort(Comparator.comparingLong((Finding f) -> f.waste).reversed());
    leaks.sort(Comparator.comparingLong((Finding f) -> f.waste).reversed());
    StringBuilder sb = new StringBuilder();
    sb.append("Memory inspections of a heap of ").append(heap).append(" bytes. The estimates are upper bounds and "
        + "overlap, so do not add them up: the array behind an ArrayList is both a sparse array and part of the "
        + "collection's waste, and a duplicated string may sit in either.\n\n");
    sb.append("## Memory waste\n\n| inspection | found | est. waste (bytes) | % of heap | details |\n"
        + "|---|---|---|---|---|\n");
    for (Finding f : waste) {
      sb.append("| ").append(f.name).append(" | ").append(f.found).append(" | ").append(f.waste).append(" | ")
          .append(AnalysisTools.percent(f.waste, heap)).append(" | ").append(f.tool).append(" |\n");
    }
    if (!leaks.isEmpty()) {
      sb.append("\n## Possible leaks\n\n| inspection | found | retained bytes | % of heap | details |\n"
          + "|---|---|---|---|---|\n");
      for (Finding f : leaks) {
        sb.append("| ").append(f.name).append(" | ").append(f.found).append(" | ")
            .append(f.waste == 0 ? "" : String.valueOf(f.waste)).append(" | ")
            .append(f.waste == 0 ? "" : AnalysisTools.percent(f.waste, heap)).append(" | ").append(f.tool)
            .append(" |\n");
      }
    }
    for (String failure : failures) {
      sb.append("\nNot run to the end: ").append(failure);
    }
    sb.append("\n\nThe last column names the tool that lists the examples, with addresses. For leaks as such, "
        + "leak_suspects is the report to read.");
    return sb.toString();
  }

  // ---------------------------------------------------------------- helpers

  private static String label(IObject object) {
    String name = object.getDisplayName();
    return name.length() > MAX_LABEL ? name.substring(0, MAX_LABEL) + "..." : name;
  }

  /** Text for a markdown cell. */
  private static String cell(String text) {
    return text.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
  }
}
