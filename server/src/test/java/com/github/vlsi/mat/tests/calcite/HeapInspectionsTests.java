package com.github.vlsi.mat.tests.calcite;

import com.github.vlsi.mat.calcite.mcp.McpServer;

import net.minidev.json.JSONValue;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The memory inspections: thread locals, array waste, null fields and the summary. They need a heap with a known
 * shape, which no dump in the repository has, so a small program builds one when the tests start: a JVM is run on
 * {@link #GENERATOR} and takes a heap dump of itself. Nothing binary is kept in the repository.
 */
public class HeapInspectionsTests {
  /** Builds the heap the tests look for; the numbers in it (2000 objects, 100 arrays...) are the ones asserted. */
  private static final String GENERATOR = """
      import java.lang.management.ManagementFactory;
      import java.util.*;
      import java.util.concurrent.CountDownLatch;
      import com.sun.management.HotSpotDiagnosticMXBean;

      public class Gen {
        /** Four reference fields that are null in almost all instances. */
        static class Sparse { Object a, b, c, d; String name = "n"; int count; }
        static final List<Object> KEEP = new ArrayList<>();
        static final ThreadLocal<byte[]> LIVE = new ThreadLocal<>();

        public static void main(String[] args) throws Exception {
          CountDownLatch ready = new CountDownLatch(1);
          CountDownLatch finish = new CountDownLatch(1);
          Thread worker = new Thread(() -> {
            LIVE.set(new byte[100_000]);                      // in use: the ThreadLocal is reachable through LIVE
            ThreadLocal<byte[]> temporary = new ThreadLocal<>();
            temporary.set(new byte[300_000]);                 // stale once the ThreadLocal is collected
            temporary = null;
            ready.countDown();
            try { finish.await(); } catch (InterruptedException e) { }
          }, "gen-worker");
          worker.start();
          ready.await();
          for (int i = 0; i < 2000; i++) {
            Sparse s = new Sparse();
            if (i % 20 == 0) { s.a = "x"; s.b = "y"; }       // a and b are null in 1900 of 2000, c and d in all
            KEEP.add(s);
          }
          for (int i = 0; i < 500; i++) { KEEP.add(new Object[0]); KEEP.add(new int[0]); }
          for (int i = 0; i < 100; i++) { Object[] a = new Object[100]; a[i % 100] = "v"; KEEP.add(a); }
          for (int i = 0; i < 100; i++) { long[] l = new long[1000]; l[5] = i + 1; KEEP.add(l); }
          for (int i = 0; i < 50; i++) { byte[] b = new byte[1000]; Arrays.fill(b, (byte) 7); KEEP.add(b); }
          for (int i = 0; i < 1000; i++) KEEP.add(new String("duplicated-text"));
          for (int i = 0; i < 300; i++) KEEP.add(new ArrayList<String>(50));
          ManagementFactory.newPlatformMXBeanProxy(ManagementFactory.getPlatformMBeanServer(),
              "com.sun.management:type=HotSpotDiagnostic", HotSpotDiagnosticMXBean.class).dumpHeap(args[0], true);
          finish.countDown();
        }
      }
      """;

  private static McpServer server;
  private static File directory;
  /** The generated heap, and the small sample dump of the repository. */
  private static String generated;
  private static String sample;

  @BeforeClass
  public static void openHeaps() throws Exception {
    Assume.assumeTrue("a JDK is needed to generate a heap", ToolProvider.getSystemJavaCompiler() != null);
    server = new McpServer();
    directory = Files.createTempDirectory("matmcp-inspections").toFile();
    File source = new File(directory, "Gen.java");
    Files.write(source.toPath(), GENERATOR.getBytes(StandardCharsets.UTF_8));
    File heap = new File(directory, "gen.hprof");
    File log = new File(directory, "gen.log");
    Process process = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(), "-Xmx256m",
        source.getPath(), heap.getPath()).directory(directory).redirectErrorStream(true).redirectOutput(log).start();
    Assert.assertTrue("the generator did not finish", process.waitFor(180, TimeUnit.SECONDS));
    Assert.assertEquals(new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8), 0, process.exitValue());
    Assert.assertTrue(heap.isFile());
    generated = heap.getAbsolutePath();
    sample = new File(System.getProperty("test.dumps", "dumps"), "mvn1m_jdk18.hprof").getAbsolutePath();
    for (String path : new String[] {generated, sample}) {
      Map<?, ?> opened = call("open_heap_dump", "{\"path\":" + JSONValue.toJSONString(path) + "}");
      Assert.assertEquals(text(opened), Boolean.FALSE, opened.get("isError"));
    }
  }

  @AfterClass
  public static void closeHeaps() {
    if (server != null) {
      server.close();
    }
    if (directory != null) {
      File[] files = directory.listFiles();
      if (files != null) {
        for (File file : files) {
          file.delete();
        }
      }
      directory.delete();
    }
  }

  private static Map<?, ?> call(String tool, String arguments) {
    String response = server.handle("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\""
        + tool + "\",\"arguments\":" + arguments + "}}");
    Assert.assertNotNull(response);
    Map<?, ?> parsed = (Map<?, ?>) JSONValue.parse(response);
    Assert.assertNull(String.valueOf(parsed), parsed.get("error"));
    return (Map<?, ?>) parsed.get("result");
  }

  private static String text(Map<?, ?> result) {
    return (String) ((Map<?, ?>) ((List<?>) result.get("content")).get(0)).get("text");
  }

  /** The text of a tool run on the generated heap, with the other arguments given as the inside of a JSON object. */
  private static String run(String tool, String dump, String arguments) {
    Map<?, ?> result = call(tool, "{\"dump\":" + JSONValue.toJSONString(dump)
        + (arguments.isEmpty() ? "" : "," + arguments) + "}");
    Assert.assertEquals(text(result), Boolean.FALSE, result.get("isError"));
    return text(result);
  }

  private static void assertError(String tool, String arguments, String expected) {
    Map<?, ?> result = call(tool, "{\"dump\":" + JSONValue.toJSONString(generated) + "," + arguments + "}");
    Assert.assertEquals(text(result), Boolean.TRUE, result.get("isError"));
    Assert.assertTrue(text(result), text(result).contains(expected));
  }

  /** The cells of the first table row, from the section starting with the heading, that starts with the text. */
  private static String[] row(String report, String section, String start) {
    int from = section.isEmpty() ? 0 : report.indexOf(section);
    Assert.assertTrue("section " + section + " in\n" + report, from >= 0);
    int to = report.indexOf("\n## ", from + 1);
    for (String line : report.substring(from, to < 0 ? report.length() : to).split("\n")) {
      if (line.startsWith(start)) {
        String[] cells = line.split("\\|");
        for (int i = 0; i < cells.length; i++) {
          cells[i] = cells[i].trim();
        }
        return cells;
      }
    }
    throw new AssertionError("no row starting " + start + " in section " + section + " of\n" + report);
  }

  private static long number(String cell) {
    return Long.parseLong(cell.replace("~", "").trim());
  }

  // ---------------------------------------------------------------- thread locals

  @Test
  public void staleThreadLocalValuesAreFound() {
    String report = run("thread_locals", generated, "");
    Assert.assertTrue(report, report.contains("(collected)"));
    Assert.assertTrue(report, report.contains("byte[300000]"));
    // | thread | values | stale | retained bytes | stale bytes |
    String[] worker = row(report, "", "| java.lang.Thread @");
    Assert.assertTrue(worker[1], worker[1].contains("gen-worker"));
    Assert.assertEquals(2, number(worker[2]));
    Assert.assertEquals(1, number(worker[3]));
    Assert.assertTrue(worker[4], number(worker[4]) >= 400_000 && number(worker[4]) < 400_500);
    Assert.assertTrue(worker[5], number(worker[5]) >= 300_000 && number(worker[5]) < 300_500);

    String stale = run("thread_locals", generated, "\"only_stale\":true");
    Assert.assertTrue(stale, stale.contains("gen-worker"));
    Assert.assertFalse(stale, stale.contains("ThreadLocal @"));
    Assert.assertFalse("a thread without stale values is not listed: " + stale, stale.contains("  main |"));
  }

  @Test
  public void threadLocalsOfTheSampleDump() {
    String report = run("thread_locals", sample, "");
    Assert.assertTrue(report, report.contains("thread-local value") || report.startsWith("No thread holds"));
  }

  // ---------------------------------------------------------------- arrays

  @Test
  public void arrayWasteFindsZeroLengthSparseAndConstantArrays() {
    String objects = run("array_waste", generated, "\"class_regex\":\"java\\\\.lang\\\\.Object\\\\[\\\\]\"");
    // | class | arrays | bytes each | waste (bytes) |
    String[] zero = row(objects, "## Zero-length arrays", "| java.lang.Object[] |");
    Assert.assertTrue(zero[2], number(zero[2]) >= 500);
    Assert.assertEquals(16, number(zero[3]));
    Assert.assertEquals((number(zero[2]) - 1) * 16, number(zero[4]));
    // | class | arrays | slots | empty slots | waste (bytes) | worst example |
    String[] sparse = row(objects, "## Sparse arrays", "| java.lang.Object[] |");
    Assert.assertTrue(sparse[2], number(sparse[2]) >= 100);
    Assert.assertTrue(sparse[4], number(sparse[4]) >= 100 * 99);

    String longs = run("array_waste", generated, "\"class_regex\":\"long\\\\[\\\\]\"");
    String[] sparseLongs = row(longs, "## Sparse arrays", "| long[] |");
    Assert.assertTrue(sparseLongs[2], number(sparseLongs[2]) >= 100);
    Assert.assertTrue(sparseLongs[4], number(sparseLongs[4]) >= 100 * 999);
    Assert.assertEquals(number(sparseLongs[4]) * 8, number(sparseLongs[5]));
    Assert.assertTrue(sparseLongs[6], sparseLongs[6].startsWith("0x"));

    String ints = run("array_waste", generated, "\"class_regex\":\"int\\\\[\\\\]\"");
    Assert.assertTrue(number(row(ints, "## Zero-length arrays", "| int[] |")[2]) >= 500);

    String bytes = run("array_waste", generated, "\"class_regex\":\"byte\\\\[\\\\]\"");
    // | class | arrays | bytes | biggest example |
    String[] constant = row(bytes, "## Arrays whose elements are all the same", "| byte[] |");
    Assert.assertTrue(constant[2], number(constant[2]) >= 50);
    Assert.assertTrue(constant[3], number(constant[3]) >= 50 * 1000);
  }

  @Test
  public void arrayWasteCanBeNarrowedAndTuned() {
    // only arrays that are nearly all empty: the 100 long[] with one element set qualify, the Object[] with one too
    String strict = run("array_waste", generated, "\"class_regex\":\"long\\\\[\\\\]\",\"min_percent\":99");
    Assert.assertTrue(strict, strict.contains("| long[] |"));
    // arrays shorter than min_length are not judged
    String none = run("array_waste", generated, "\"class_regex\":\"long\\\\[\\\\]\",\"min_length\":100000");
    Assert.assertFalse(none, none.contains("## Sparse arrays"));
    assertError("array_waste", "\"class_regex\":\"a b\"", "must not contain spaces");
  }

  // ---------------------------------------------------------------- null fields

  @Test
  public void nullFieldsAreFoundWithTheirCounts() {
    String report = run("null_fields", generated, "\"class_regex\":\"Gen\\\\$Sparse\",\"min_instances\":10");
    // | class | field | instances | null | null % | est. waste (bytes) |
    Object[][] expected = {{"a", 2000L, 1900L, "95.0"}, {"b", 2000L, 1900L, "95.0"}, {"c", 2000L, 2000L, "100.0"},
        {"d", 2000L, 2000L, "100.0"}};
    for (Object[] e : expected) {
      String[] row = row(report, "", "| Gen$Sparse | " + e[0] + " |");
      Assert.assertEquals(e[1], number(row[3]));
      Assert.assertEquals(e[2], number(row[4]));
      Assert.assertEquals(e[3], row[5]);
      long perReference = number(row[6]) / number(row[4]);
      Assert.assertTrue("a reference is 4 or 8 bytes: " + report, perReference == 4 || perReference == 8);
    }
    // a field that is set, and a primitive one, are not reported
    Assert.assertFalse(report, report.contains("| name |"));
    Assert.assertFalse(report, report.contains("| count |"));
  }

  @Test
  public void nullFieldsLeaveOutClassMirrorsAndFollowTheThresholds() {
    String report = run("null_fields", generated, "\"max_rows\":50");
    Assert.assertFalse("the fields of java.lang.Class are the JVM's", report.contains("| java.lang.Class |"));
    Assert.assertTrue(report, report.contains("| Gen$Sparse |"));
    // a field that is null in 96% or more of the instances: only c and d
    String strict = run("null_fields", generated, "\"class_regex\":\"Gen\\\\$Sparse\",\"min_null_percent\":96");
    Assert.assertTrue(strict, strict.contains("| Gen$Sparse | c |") && strict.contains("| Gen$Sparse | d |"));
    Assert.assertFalse(strict, strict.contains("| Gen$Sparse | a |"));
    // more instances than the class has: nothing to report
    String none = run("null_fields", generated, "\"class_regex\":\"Gen\\\\$Sparse\",\"min_instances\":5000");
    Assert.assertTrue(none, none.contains("0 reference fields"));
    assertError("null_fields", "\"class_regex\":\"-x\"", "must not start with a dash");
  }

  // ---------------------------------------------------------------- the summary

  @Test
  public void memoryInspectionsRankWhatTheyFind() {
    String report = run("memory_inspections", generated, "");
    Assert.assertTrue(report, report.contains("## Memory waste"));
    Assert.assertTrue(report, report.contains("## Possible leaks"));
    Assert.assertTrue(report, report.contains("do not add them up"));
    // | inspection | found | est. waste (bytes) | % of heap | details |
    String[] sparse = row(report, "## Memory waste", "| Sparse arrays |");
    Assert.assertTrue(sparse[2], sparse[2].endsWith("arrays"));
    Assert.assertTrue(sparse[3], number(sparse[3]) >= 100 * 999 * 8);
    Assert.assertEquals("array_waste", sparse[5]);
    String[] nulls = row(report, "## Memory waste", "| Fields that are null in most objects |");
    Assert.assertTrue(nulls[3], number(nulls[3]) >= 2000 * 4 * 4 * 95 / 100);
    Assert.assertEquals("null_fields", nulls[5]);
    String[] strings = row(report, "## Memory waste", "| Duplicate strings |");
    Assert.assertTrue(strings[3], number(strings[3]) > 0);
    Assert.assertEquals("duplicate_strings", strings[5]);
    String[] collections = row(report, "## Memory waste", "| Empty and under-filled collections |");
    Assert.assertTrue(collections[3], number(collections[3]) > 0);
    String[] stale = row(report, "## Possible leaks", "| Stale thread-local values |");
    Assert.assertTrue(stale[2], stale[2].startsWith("1 of "));
    Assert.assertTrue(stale[3], number(stale[3]) >= 300_000);
    Assert.assertEquals("thread_locals", stale[5]);
    // ranked: the estimates go down the table
    long previous = Long.MAX_VALUE;
    for (String line : report.substring(report.indexOf("## Memory waste"), report.indexOf("## Possible leaks"))
        .split("\n")) {
      if (line.startsWith("| ") && !line.startsWith("| inspection")) {
        long waste = number(line.split("\\|")[3]);
        Assert.assertTrue(line, waste <= previous);
        previous = waste;
      }
    }
  }

  @Test
  public void memoryInspectionsCanBeChosen() {
    String only = run("memory_inspections", generated, "\"inspections\":\"null_fields, thread_locals\"");
    Assert.assertTrue(only, only.contains("| Fields that are null in most objects |"));
    Assert.assertTrue(only, only.contains("| Stale thread-local values |"));
    Assert.assertFalse(only, only.contains("| Sparse arrays |"));
    Assert.assertFalse(only, only.contains("| Duplicate strings |"));
    assertError("memory_inspections", "\"inspections\":\"null_fields,nonsense\"", "inspections must be a list of");
  }

  @Test
  public void memoryInspectionsOfTheSampleDump() {
    String report = run("memory_inspections", sample, "");
    Assert.assertTrue(report, report.contains("## Memory waste"));
    Assert.assertTrue(report, report.contains("| Duplicate strings |"));
    Assert.assertTrue(report, report.contains("| Classes defined by several class loaders |"));
    Assert.assertFalse(report, report.contains("Not run to the end"));
    Assert.assertTrue(run("array_waste", sample, "").contains("arrays ("));
    Assert.assertTrue(run("null_fields", sample, "").contains("reference fields"));
  }
}
