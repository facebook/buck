package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Utility to print tables. */
class StarlarkRuntimeStatsTable {

  static <R, S extends Comparable<S>> void printTableTopBy(
      PrintStream printStream,
      ImmutableList<R> rows,
      ImmutableList<Column<R>> columns,
      int top,
      Function<R, S> topBy) {
    ImmutableList<R> topRows = rows.stream()
        .sorted(Comparator.comparing(topBy).reversed())
        .limit(top)
        .collect(ImmutableList.toImmutableList());
    printTable(printStream, topRows, columns);
  }

  static <K, V> void printTable(
      PrintStream out,
      ImmutableList<? extends Map.Entry<K, V>> entries,
      String keyName,
      String valueName) {
    printTable(
        out,
        entries,
        new String[] { keyName, valueName },
        e -> new Object[] { e.getKey(), e.getValue() });
  }

  static <R> void printTable(
      PrintStream printStream,
      ImmutableList<R> rows,
      ImmutableList<Column<R>> columns
  ) {
    Preconditions.checkArgument(!columns.isEmpty());

    printTable(
        printStream,
        rows,
        columns.stream().map(c -> c.name).toArray(String[]::new),
        r -> columns.stream().map(c -> c.get.apply(r)).toArray());
  }

  static <R> void printTable(
      PrintStream printStream,
      ImmutableList<R> rows, String[] columnNames, Function<R, Object[]> columns) {
    int[] maxWidthByColumn = Arrays.stream(columnNames).mapToInt(String::length).toArray();
    for (R row : rows) {
      Object[] cells = columns.apply(row);
      Verify.verify(cells.length == maxWidthByColumn.length);
      for (int i = 0, cellsLength = cells.length; i < cellsLength; i++) {
        Object cell = cells[i];
        maxWidthByColumn[i] = Math.max(maxWidthByColumn[i], Objects.toString(cell).length());
      }
    }

    StringBuilder out = new StringBuilder();
    appendRow(out, maxWidthByColumn, columnNames);
    for (R row : rows) {
      appendRow(out, maxWidthByColumn, columns.apply(row));
    }
    printStream.print(out);
  }

  private static void appendRow(StringBuilder sb, int[] maxWidthByColumn, Object[] row) {
    for (int i = 0; i < maxWidthByColumn.length; i++) {
      int w = maxWidthByColumn[i];
      Object value = row[i];
      String valueStr = Objects.toString(value);
      boolean leftPad = value instanceof Number;
      int padSize = Math.max(0, w - valueStr.length());
      if (i != 0) {
        sb.append("  ");
      }
      if (leftPad) {
        for (int j = 0; j < padSize; ++j) {
          sb.append(" ");
        }
      }
      sb.append(valueStr);
      if (!leftPad && i != maxWidthByColumn.length - 1) {
        for (int j = 0; j < padSize; ++j) {
          sb.append(" ");
        }
      }
    }
    sb.append("\n");
  }

  /** Column description. */
  static class Column<R> {
    private final String name;
    private final Function<R, Object> get;

    public Column(String name, Function<R, Object> get) {
      this.name = name;
      this.get = get;
    }
  }

  static <R> Column<R> column(String name, Function<R, Object> get) {
    return new Column<>(name, get);
  }
}
