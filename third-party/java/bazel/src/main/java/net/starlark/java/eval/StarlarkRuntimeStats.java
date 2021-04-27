package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.starlark.java.syntax.TokenKind;

/** Some Starlark runtime statistics. */
public class StarlarkRuntimeStats {

  private StarlarkRuntimeStats() {}

  @Nullable
  private static String getStarlarkRtStats() {
    String prop = System.getProperty("starlark.rt.stats");
    if (prop != null) {
      return prop;
    }
    return System.getenv("STARLARK_RT_STATS");
  }

  private static final String STARLARK_RT_STATS = getStarlarkRtStats();


  /**
   * Whether statistics enabled. This is initialized from property {@code starlark.rt.stats} or from
   * env variable {@code STARLARK_RT_STATS}.
   */
  public static final boolean ENABLED = STARLARK_RT_STATS != null;

  static {
    if (ENABLED) {
      System.err.println();
      System.err.println("Collecting Starlark runtime stats.");
      System.err.println();
    }
  }

  private static StarlarkRuntimeStats stats = ENABLED ? new StarlarkRuntimeStats() : null;

  private static class NativeCallStats {
    private final AtomicInteger count = new AtomicInteger();
    private final AtomicLong totalDurationNanos = new AtomicLong();

    long totalDurationMillis() {
      return totalDurationNanos.get() / 1_000_000;
    }

    long avgDurationNanos() {
      int count = this.count.get();
      return count != 0 ? totalDurationNanos.get() / count : 0;
    }

    NativeCallStats copy() {
      NativeCallStats copy = new NativeCallStats();
      copy.count.set(count.get());
      copy.totalDurationNanos.set(totalDurationNanos.get());
      return copy;
    }
  }

  private static class StarlarkCallStats {
    private final AtomicInteger count = new AtomicInteger();
    private final AtomicLong steps = new AtomicLong();

    long avgSteps() {
      int count = this.count.get();
      return count != 0 ? steps.get() / count : 0;
    }

    StarlarkCallStats copy() {
      StarlarkCallStats copy = new StarlarkCallStats();
      copy.count.set(count.get());
      copy.steps.set(steps.get());
      return copy;
    }
  }

  private ConcurrentHashMap<String, NativeCallStats> nativeCalls = new ConcurrentHashMap<>();
  private ConcurrentHashMap<String, StarlarkCallStats> starlarkCalls = new ConcurrentHashMap<>();
  private AtomicIntegerArray instructions = new AtomicIntegerArray(BcInstr.Opcode.values().length);
  private AtomicIntegerArray binaryOps = new AtomicIntegerArray(TokenKind.values().length);
  private AtomicLong compileTimeNanos = new AtomicLong();

  static void recordNativeCall(String name, long durationNanos) {
    if (!ENABLED) {
      return;
    }

    NativeCallStats callStats = stats.nativeCalls.computeIfAbsent(name, k -> new NativeCallStats());
    callStats.count.addAndGet(1);
    callStats.totalDurationNanos.addAndGet(durationNanos);
  }

  static void recordStarlarkCall(String name, int steps) {
    if (!ENABLED) {
      return;
    }

    StarlarkCallStats callStats =
        stats.starlarkCalls.computeIfAbsent(name, k -> new StarlarkCallStats());
    callStats.count.addAndGet(1);
    callStats.steps.addAndGet(steps);
  }

  static void recordInst(int opcode) {
    if (!ENABLED) {
      return;
    }

    stats.instructions.addAndGet(opcode, 1);
  }

  static void recordBinaryOp(TokenKind op) {
    if (!ENABLED) {
      return;
    }

    stats.binaryOps.addAndGet(op.ordinal(), 1);
  }

  public static void recordCompileTimeNanos(long compileTimeNanos) {
    if (!ENABLED) {
      return;
    }

    stats.compileTimeNanos.addAndGet(compileTimeNanos);
  }

  public static void printStatsAndReset() {
    if (!ENABLED) {
      return;
    }

    StarlarkRuntimeStats stats = StarlarkRuntimeStats.stats;
    StarlarkRuntimeStats.stats = new StarlarkRuntimeStats();

    Preconditions.checkState(STARLARK_RT_STATS != null);
    if (STARLARK_RT_STATS.equals("true") || STARLARK_RT_STATS.equals("1")) {
      System.err.println();
      System.err.println();
      stats.printStats(System.err);
      System.err.println();
    } else {
      System.err.println("Writing starlark runtime stats to " + STARLARK_RT_STATS);
      try {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(byteArrayOutputStream);
        stats.printStats(printStream);
        printStream.flush();
        Files.write(Paths.get(STARLARK_RT_STATS), byteArrayOutputStream.toByteArray());
      } catch (Exception e) {
        throw new RuntimeException("Failed to write stats", e);
      }
    }
  }

  private void printStats(PrintStream out) {
    out.println("Starlark stats:");
    out.println();
    out.println("Compile time ms: " + compileTimeNanos.get() / 1_000_000);

    printCallStats(out);
    printInstructionStats(out);
    printBinaryOpStats(out);
  }

  private void printCallStats(PrintStream out) {
    int top = 50;

    // Take a snapshot, otherwise we are not allowed to perform sort
    ImmutableList<AbstractMap.SimpleEntry<String, NativeCallStats>> nativeCalls =
        this.nativeCalls.entrySet().stream()
            .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue().copy()))
            .collect(ImmutableList.toImmutableList());
    long totalNativeDurationNanos =
        nativeCalls.stream().mapToLong(c -> c.getValue().totalDurationNanos.get()).sum();

    ImmutableList<AbstractMap.SimpleEntry<String, StarlarkCallStats>> starlarkCalls =
        this.starlarkCalls.entrySet().stream()
            .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue().copy()))
            .collect(ImmutableList.toImmutableList());

    ImmutableList<AbstractMap.SimpleEntry<String, NativeCallStats>> topNativeByCount =
        nativeCalls.stream()
            .sorted(
                Comparator.comparing(
                        (AbstractMap.SimpleEntry<String, NativeCallStats> e) -> {
                          return e.getValue().count.get();
                        })
                    .reversed())
            .limit(top)
            .collect(ImmutableList.toImmutableList());

    ImmutableList<AbstractMap.SimpleEntry<String, NativeCallStats>> topNativeByDuration =
        nativeCalls.stream()
            .sorted(
                Comparator.comparing(
                        (AbstractMap.SimpleEntry<String, NativeCallStats> e) -> {
                          return e.getValue().totalDurationNanos.get();
                        })
                    .reversed())
            .limit(top)
            .collect(ImmutableList.toImmutableList());

    ImmutableList<AbstractMap.SimpleEntry<String, StarlarkCallStats>> topStarlarkByCount =
        starlarkCalls.stream()
            .sorted(
                Comparator.comparing(
                        (AbstractMap.SimpleEntry<String, StarlarkCallStats> e) -> {
                          return e.getValue().count.get();
                        })
                    .reversed())
            .limit(top)
            .collect(ImmutableList.toImmutableList());

    long totalNativeCalls = nativeCalls.stream().mapToLong(e -> e.getValue().count.get()).sum();
    long totalStarlarkCalls = starlarkCalls.stream().mapToLong(e -> e.getValue().count.get()).sum();

    out.println();
    out.println("Total native calls: " + totalNativeCalls);
    out.println(
        "Total time spent in native calls, ms: " + totalNativeDurationNanos / 1_000_000);

    out.println();
    out.println("Top " + top + " native calls by total duration:");
    printTable(
        out,
        topNativeByDuration,
        new String[] {
          "name", "tot_ms", "count", "avg_ns",
        },
        e ->
            new Object[] {
              e.getKey(),
              e.getValue().totalDurationMillis(),
              e.getValue().count,
              e.getValue().avgDurationNanos()
            });

    out.println();
    out.println("Top " + top + " native calls by count:");
    printTable(
        out,
        topNativeByCount,
        new String[] {
          "name", "count", "tot_ms", "avg_ns",
        },
        e ->
            new Object[] {
              e.getKey(),
              e.getValue().count,
              e.getValue().totalDurationMillis(),
              e.getValue().avgDurationNanos()
            });

    ImmutableList<AbstractMap.SimpleEntry<String, StarlarkCallStats>> topStarlarkByTotalSteps =
        starlarkCalls.stream()
            .sorted(
                Comparator.comparing(
                        (AbstractMap.SimpleEntry<String, StarlarkCallStats> e) -> {
                          return e.getValue().steps.get();
                        })
                    .reversed())
            .limit(top)
            .collect(ImmutableList.toImmutableList());

    out.println();
    out.println("Total starlark calls: " + totalStarlarkCalls);
    out.println();
    out.println("Top " + top + " starlark calls by total steps:");
    printTable(
        out,
        topStarlarkByTotalSteps,
        new String[] {
          "name", "steps_tot", "steps_avg", "count",
        },
        e ->
            new Object[] {
              e.getKey(), e.getValue().steps, e.getValue().avgSteps(), e.getValue().count,
            });

    out.println();
    out.println("Top " + top + " starlark calls by count:");
    printTable(
        out,
        topStarlarkByCount,
        new String[] {
          "name", "count", "steps_tot", "steps_avg",
        },
        e ->
            new Object[] {
              e.getKey(), e.getValue().count, e.getValue().steps, e.getValue().avgSteps(),
            });
  }

  private void printInstructionStats(PrintStream out) {
    ImmutableList<AbstractMap.SimpleEntry<BcInstr.Opcode, Integer>> instructionsCountByOpcode =
        Arrays.stream(BcInstr.Opcode.values())
            .map(o -> new AbstractMap.SimpleEntry<>(o, this.instructions.get(o.ordinal())))
            .sorted(
                Comparator.comparing(AbstractMap.SimpleEntry<BcInstr.Opcode, Integer>::getValue)
                    .reversed())
            .collect(ImmutableList.toImmutableList());

    long totalStarlarkSteps =
        instructionsCountByOpcode.stream().mapToLong(AbstractMap.SimpleEntry::getValue).sum();

    out.println();
    out.println("Total starlark instruction steps: " + totalStarlarkSteps);
    out.println();
    out.println("Instruction step count by opcode:");
    printTable(out, instructionsCountByOpcode, "opcode", "count");
  }

  private void printBinaryOpStats(PrintStream out) {
    ImmutableList<AbstractMap.SimpleEntry<TokenKind, Integer>> binaryOpCountByOp =
        Arrays.stream(TokenKind.values())
            .map(o -> new AbstractMap.SimpleEntry<>(o, this.binaryOps.get(o.ordinal())))
            .filter(e -> e.getValue() != 0)
            .sorted(Comparator.comparing(AbstractMap.SimpleEntry<TokenKind, Integer>::getValue)
                .reversed())
            .collect(ImmutableList.toImmutableList());

    long totalBinaryOps =
        binaryOpCountByOp.stream().mapToLong(AbstractMap.SimpleEntry::getValue).sum();

    out.println();
    out.println("Total " + BcInstr.Opcode.BINARY + " ops: " + totalBinaryOps);
    out.println();
    out.println("Binary ops by " + TokenKind.class.getSimpleName() + ":");
    printTable(out, binaryOpCountByOp, "bin_op", "count");
  }

  private <K, V> void printTable(
      PrintStream out,
      ImmutableList<AbstractMap.SimpleEntry<K, V>> entries,
      String keyName,
      String valueName) {
    printTable(
        out,
        entries,
        new String[] { keyName, valueName },
        e -> new Object[] { e.getKey(), e.getValue() });
  }

  private <R> void printTable(
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
}
