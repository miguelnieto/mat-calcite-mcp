package com.github.vlsi.mat.calcite.mcp;

import org.eclipse.mat.query.Bytes;
import org.eclipse.mat.query.Column;
import org.eclipse.mat.query.IDecorator;
import org.eclipse.mat.query.IResult;
import org.eclipse.mat.query.IStructuredResult;
import org.eclipse.mat.query.refined.RefinedResultBuilder;
import org.eclipse.mat.query.refined.RefinedStructuredResult;
import org.eclipse.mat.query.refined.RefinedTable;
import org.eclipse.mat.query.refined.RefinedTree;
import org.eclipse.mat.query.results.CompositeResult;
import org.eclipse.mat.query.results.TextResult;
import org.eclipse.mat.report.QuerySpec;
import org.eclipse.mat.report.SectionSpec;
import org.eclipse.mat.report.Spec;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.query.SnapshotQuery;
import org.eclipse.mat.util.IProgressListener;
import org.eclipse.mat.util.VoidProgressListener;

import java.util.List;

/**
 * Renders the result of a MAT query as text for an AI assistant: tables as markdown, trees as indented lines. Uses
 * MAT's own formatting, so object cells read like MAT's labels ({@code java.util.HashMap @ 0x7bfeb5848}).
 */
final class MatResultRenderer {
  private static final int MAX_CELL_LENGTH = 200;

  private final int maxRows;
  private final int maxDepth;
  private final int maxChildren;
  private int rows;
  /** Rows or tree children were left out. */
  private boolean truncated;
  /** Tree nodes below the depth limit were left out. */
  private boolean deeper;
  /** Label of the column to sort by, largest first, or null for the result's own order. */
  private String sortColumn;
  /** Where MAT queries that a report part names are run and cancelled. */
  private IProgressListener listener = new VoidProgressListener();

  /**
   * @param maxRows     rows of a table, or lines of a tree
   * @param maxDepth    levels of a tree below the top elements (0 shows only the top elements)
   * @param maxChildren children shown per tree node
   */
  MatResultRenderer(int maxRows, int maxDepth, int maxChildren) {
    this.maxRows = maxRows;
    this.maxDepth = maxDepth;
    this.maxChildren = maxChildren;
  }

  /**
   * Sorts tables and trees by the column with this label, largest first. MAT translates its labels in language
   * packs, so without a column of that label the first column of byte sizes is used: "Shallow Heap" is one.
   */
  MatResultRenderer sortedBy(String columnLabel) {
    this.sortColumn = columnLabel;
    return this;
  }

  /** Runs the queries that report parts name (see {@link #render}) with this listener, so a cancel reaches them. */
  MatResultRenderer withListener(IProgressListener progressListener) {
    this.listener = progressListener;
    return this;
  }

  String render(ISnapshot snapshot, IResult result) {
    if (result instanceof TextResult) {
      TextResult text = (TextResult) result;
      return limitLines(text.isHtml() ? htmlToText(text.getText()) : text.getText());
    }
    if (result instanceof SectionSpec || result instanceof QuerySpec || result instanceof CompositeResult) {
      StringBuilder sb = new StringBuilder();
      renderReport(sb, snapshot, result, 2);
      return sb.toString().trim();
    }
    if (!(result instanceof IStructuredResult)) {
      return result == null ? "(no result)" : result.getClass().getSimpleName() + ": " + result;
    }
    RefinedResultBuilder builder = new RefinedResultBuilder(new HeapQueryContext(snapshot), (IStructuredResult) result);
    if (sortColumn != null) {
      Column[] columns = ((IStructuredResult) result).getColumns();
      int index = -1;
      for (int i = 0; i < columns.length && index < 0; i++) {
        if (columns[i].getLabel().equals(sortColumn)) {
          index = i;
        }
      }
      for (int i = 0; i < columns.length && index < 0; i++) {
        if (Bytes.class.equals(columns[i].getType())) {
          index = i;
        }
      }
      if (index >= 0) {
        builder.setSortOrder(index, Column.SortDirection.DESC);
      }
    }
    RefinedStructuredResult refined = builder.build();
    StringBuilder sb = new StringBuilder();
    if (refined instanceof RefinedTree) {
      renderTree(sb, (RefinedTree) refined);
    } else {
      renderTable(sb, (RefinedTable) refined);
    }
    sb.append('\n').append(rows).append(rows == 1 ? " row" : " rows");
    if (truncated) {
      sb.append(" shown; more exist (raise max_rows").append(refined instanceof RefinedTree ? " or max_children)" : ")");
    }
    if (deeper) {
      sb.append("; ").append("deeper levels not shown (raise depth)");
    }
    return sb.toString();
  }

  /**
   * MAT's report results: sections that hold named parts, each a table, a tree, text, or a MAT command to run (as
   * in thread_details and leakhunter). Each part is rendered with the same limits and gets a markdown heading.
   */
  private void renderReport(StringBuilder sb, ISnapshot snapshot, IResult result, int level) {
    if (result instanceof SectionSpec) {
      SectionSpec section = (SectionSpec) result;
      heading(sb, section.getName(), level);
      for (Spec child : section.getChildren()) {
        renderReport(sb, snapshot, child, level + 1);
      }
    } else if (result instanceof QuerySpec) {
      QuerySpec query = (QuerySpec) result;
      heading(sb, query.getName(), level);
      IResult part = query.getResult();
      if (part == null && query.getCommand() != null) {
        part = runPartCommand(sb, snapshot, query.getCommand());
      }
      if (part != null) {
        renderReport(sb, snapshot, part, level + 1);
      }
    } else if (result instanceof CompositeResult) {
      CompositeResult composite = (CompositeResult) result;
      heading(sb, composite.getName(), level);
      for (CompositeResult.Entry entry : composite.getResultEntries()) {
        heading(sb, entry.getName(), level + 1);
        renderReport(sb, snapshot, entry.getResult(), level + 2);
      }
    } else if (result instanceof TextResult || result instanceof IStructuredResult) {
      sb.append(new MatResultRenderer(maxRows, maxDepth, maxChildren).sortedBy(sortColumn).withListener(listener)
          .render(snapshot, result)).append("\n\n");
    } else {
      // charts and other results that have no text form
      sb.append("(not shown: ").append(result == null ? "empty" : result.getClass().getSimpleName()).append(")\n\n");
    }
  }

  /** At most max_rows lines of a text, with a note of how many were left out. */
  private String limitLines(String text) {
    if (text == null) {
      return "";
    }
    String[] lines = text.split("\r?\n", -1);
    if (lines.length <= maxRows) {
      return text;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < maxRows; i++) {
      sb.append(lines[i]).append('\n');
    }
    return sb.append("... ").append(lines.length - maxRows).append(" more lines (raise max_rows)").toString();
  }

  /** The text of the small HTML that MAT writes into report parts: line breaks for blocks and items, no tags. */
  static String htmlToText(String html) {
    if (html == null) {
      return "";
    }
    String text = html
        .replaceAll("(?is)<a\\s+href=\"mat://[^\"]*\"[^>]*>.*?</a>\\.?", "") // links into MAT's UI
        .replaceAll("(?i)<li[^>]*>", "\n- ")
        .replaceAll("(?i)</?(p|ul|ol|div|br|h[1-6]|tr|table)[^>]*>", "\n")
        .replaceAll("(?s)<[^>]*>", "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&nbsp;", " ").replace("&amp;", "&");
    return text.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n\\s*\\n- ", "\n- ").replaceAll("\\n{3,}", "\n\n")
        .trim();
  }

  /** Runs the command of a report part, if mat_query would allow it. Returns null if it cannot be shown. */
  private IResult runPartCommand(StringBuilder sb, ISnapshot snapshot, String command) {
    try {
      ObjectTools.checkAllowed(command, snapshot);
      return SnapshotQuery.parse(command, snapshot).execute(listener);
    } catch (Exception e) {
      sb.append("(not shown: ").append(cell(command)).append(": ").append(e.getMessage()).append(")\n\n");
      return null;
    }
  }

  private static void heading(StringBuilder sb, String name, int level) {
    if (name == null || name.trim().isEmpty()) {
      return;
    }
    for (int i = 0; i < Math.min(level, 6); i++) {
      sb.append('#');
    }
    sb.append(' ').append(cell(name)).append("\n\n");
  }

  private void renderTable(StringBuilder sb, RefinedTable table) {
    Column[] columns = table.getColumns();
    sb.append('|');
    for (Column column : columns) {
      sb.append(' ').append(cell(column.getLabel())).append(" |");
    }
    sb.append("\n|");
    for (int i = 0; i < columns.length; i++) {
      sb.append("---|");
    }
    sb.append('\n');
    for (Object row : table.getRows()) {
      if (rows == maxRows) {
        truncated = true;
        return;
      }
      sb.append('|');
      for (int i = 0; i < columns.length; i++) {
        sb.append(' ').append(cell(value(table, columns[i], row, i))).append(" |");
      }
      sb.append('\n');
      rows++;
    }
  }

  private void renderTree(StringBuilder sb, RefinedTree tree) {
    Column[] columns = tree.getColumns();
    for (int i = 0; i < columns.length; i++) {
      sb.append(i == 0 ? "" : " | ").append(columns[i].getLabel());
    }
    sb.append('\n');
    renderNodes(sb, tree, tree.getElements(), 0);
  }

  private void renderNodes(StringBuilder sb, RefinedTree tree, List<?> nodes, int depth) {
    int shown = 0;
    for (Object node : nodes) {
      if (rows == maxRows) {
        truncated = true;
        return;
      }
      if (depth > 0 && shown == maxChildren) {
        truncated = true;
        indent(sb, depth).append("... ").append(nodes.size() - shown).append(" more\n");
        return;
      }
      indent(sb, depth);
      Column[] columns = tree.getColumns();
      for (int i = 0; i < columns.length; i++) {
        sb.append(i == 0 ? "" : " | ").append(cell(value(tree, columns[i], node, i)));
      }
      sb.append('\n');
      rows++;
      shown++;
      if (tree.hasChildren(node)) {
        if (depth < maxDepth) {
          renderNodes(sb, tree, tree.getChildren(node), depth + 1);
        } else {
          deeper = true;
        }
      }
    }
  }

  /** The formatted value with the column's decorations, e.g. MAT's GC root marks on a path to GC roots. */
  private static String value(RefinedStructuredResult result, Column column, Object row, int index) {
    String text = result.getFormattedColumnValue(row, index);
    IDecorator decorator = column.getDecorator();
    if (decorator == null) {
      return text;
    }
    String prefix = decorator.prefix(row);
    String suffix = decorator.suffix(row);
    return (prefix == null ? "" : prefix + " ") + text + (suffix == null ? "" : " " + suffix);
  }

  private static StringBuilder indent(StringBuilder sb, int depth) {
    for (int i = 0; i < depth; i++) {
      sb.append("  ");
    }
    return sb;
  }

  private static String cell(String text) {
    String value = text == null ? "" : text;
    if (value.length() > MAX_CELL_LENGTH) {
      value = value.substring(0, MAX_CELL_LENGTH) + "...";
    }
    return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
  }
}
