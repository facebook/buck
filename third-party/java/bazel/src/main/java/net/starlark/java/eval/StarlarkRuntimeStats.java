package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
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

  private ConcurrentLinkedDeque<String> defs = new ConcurrentLinkedDeque<>();

  static void addDef(String def) {
    if (!ENABLED) {
      return;
    }

    stats.defs.add(def);
  }

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
    private final AtomicLong totalDurationNanos = new AtomicLong();

    long avgSteps() {
      int count = this.count.get();
      return count != 0 ? steps.get() / count : 0;
    }

    long avgDurationNanos() {
      int count = this.count.get();
      return count != 0 ? totalDurationNanos.get() / count : 0;
    }

    long totalDurationMillis() {
      return totalDurationNanos.get() / 1_000_000;
    }

    StarlarkCallStats copy() {
      StarlarkCallStats copy = new StarlarkCallStats();
      copy.count.set(count.get());
      copy.steps.set(steps.get());
      copy.totalDurationNanos.set(totalDurationNanos.get());
      return copy;
    }
  }

  private static class CachedCallStats {
    private final AtomicIntegerArray countByResult =
        new AtomicIntegerArray(CallCachedResult.values().length);

    int countByResult(CallCachedResult result) {
      return countByResult.get(result.ordinal());
    }

    CachedCallStats copy() {
      CachedCallStats copy = new CachedCallStats();
      for (CallCachedResult result : CallCachedResult.values()) {
        copy.countByResult.set(result.ordinal(), countByResult.get(result.ordinal()));
      }
      return copy;
    }
  }

  enum CallCachedResult {
    /** Cache hit. */
    HIT,
    /** Skip cache. */
    SKIP,
    /** Computed value stored. */
    STORE,
    /** Computed value is not stored because value is mutable. */
    MUTABLE,
    /** Computed value is not stored because of computation side effect. */
    SIDE_EFFECT,
  }

  private ConcurrentHashMap<String, NativeCallStats> nativeCalls = new ConcurrentHashMap<>();
  private ConcurrentHashMap<String, StarlarkCallStats> starlarkCalls = new ConcurrentHashMap<>();
  private ConcurrentHashMap<String, CachedCallStats> cachedCalls = new ConcurrentHashMap<>();
  private AtomicIntegerArray instructions = new AtomicIntegerArray(BcInstrOpcode.values().length);
  private AtomicIntegerArray binaryOps = new AtomicIntegerArray(TokenKind.values().length);

  static void leaveNativeCall(String name) {
    if (!ENABLED) {
      return;
    }

    long durationNanos = StarlarkRuntimeStats.leave();

    NativeCallStats callStats = stats.nativeCalls.computeIfAbsent(name, k -> new NativeCallStats());
    callStats.count.addAndGet(1);
    callStats.totalDurationNanos.addAndGet(durationNanos);
  }

  static void leaveStarlarkCall(String name, int steps) {
    if (!ENABLED) {
      return;
    }

    long durationNanos = StarlarkRuntimeStats.leave();

    StarlarkCallStats callStats =
        stats.starlarkCalls.computeIfAbsent(name, k -> new StarlarkCallStats());
    callStats.count.addAndGet(1);
    callStats.steps.addAndGet(steps);
    callStats.totalDurationNanos.addAndGet(durationNanos);
  }

  static void recordCallCached(String name, CallCachedResult result) {
    if (!ENABLED) {
      return;
    }

    CachedCallStats callStats = stats.cachedCalls
        .computeIfAbsent(name, k -> new CachedCallStats());

    callStats.countByResult.addAndGet(result.ordinal(), 1);
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

  enum WhereWeAre {
    NATIVE_CALL,
    BC_EVAL,
    BC_COMPILE,
    DEF_LINK,
    DEF_PREPARE_ARGS,
  }

  private final AtomicLongArray whereDurationNanos = new AtomicLongArray(WhereWeAre.values().length);
  private final AtomicLongArray whereCounts = new AtomicLongArray(WhereWeAre.values().length);

  private static class WhereWeAreThreadLocal {
    private WhereWeAre[] whereStack = new WhereWeAre[20];
    private int stackSize = 0;
    private long startNanos;

    void push(WhereWeAre state) {
      if (stackSize == whereStack.length) {
        whereStack = Arrays.copyOf(whereStack, whereStack.length << 1);
      }
      whereStack[stackSize++] = state;
    }

    WhereWeAre pop() {
      if (stackSize == 0) {
        throw new IllegalStateException("pop off empty stack");
      } else {
        return whereStack[--stackSize];
      }
    }

    @Nullable
    WhereWeAre top() {
      if (stackSize == 0) {
        return null;
      } else {
        return whereStack[stackSize - 1];
      }
    }
  }

  private static final ThreadLocal<WhereWeAreThreadLocal> whereWeAreThreaLocal = ThreadLocal
      .withInitial(WhereWeAreThreadLocal::new);

  static void enter(WhereWeAre where) {
    if (!ENABLED) {
      return;
    }

    long now = System.nanoTime();

    WhereWeAreThreadLocal state = whereWeAreThreaLocal.get();

    WhereWeAre top = state.top();
    if (top != null) {
      stats.whereDurationNanos.addAndGet(top.ordinal(), now - state.startNanos);
    }
    stats.whereCounts.addAndGet(where.ordinal(), 1);
    state.push(where);
    state.startNanos = now;
  }

  static long leave() {
    if (!ENABLED) {
      return 0;
    }

    long now = System.nanoTime();

    WhereWeAreThreadLocal state = whereWeAreThreaLocal.get();
    long durationNanos = now - state.startNanos;
    WhereWeAre top = state.pop();
    stats.whereDurationNanos.addAndGet(top.ordinal(), durationNanos);
    state.startNanos = now;
    return durationNanos;
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

    printTimeStats(out);
    printNativeCallStats(out);
    printStarlarkCallStats(out);
    printCallCachedStats(out);
    printInstructionStats(out);
    printBinaryOpStats(out);
    printDefs(out);
  }

  private void printTimeStats(PrintStream out) {
    class WhereTriple {
      final WhereWeAre cat;
      final long durationNanos;
      final long count;

      public WhereTriple(WhereWeAre cat, long durationNanos, long count) {
        this.cat = cat;
        this.durationNanos = durationNanos;
        this.count = count;
      }
    }

    ImmutableList<WhereTriple> stats =
        Arrays.stream(WhereWeAre.values())
            .map(cat -> {
              return new WhereTriple(cat, whereDurationNanos.get(cat.ordinal()),
                  whereCounts.get(cat.ordinal()));
            })
            .collect(ImmutableList.toImmutableList());
    long totalTimeNanos = stats.stream().mapToLong(t -> t.durationNanos).sum();

    out.println();
    out.println("Total time spent in Starlark (except parser), ms: " + (totalTimeNanos / 1_000_000));
    out.println();
    out.println("Time spent by category:");
    StarlarkRuntimeStatsTable.printTable(
        out,
        stats,
        new String[] { WhereWeAre.class.getSimpleName(), "tot_ms", "count" },
        t -> new Object[] { t.cat, t.durationNanos / 1_000_000, t.count });
  }

  private static final int TOP = 50;

  private void printNativeCallStats(PrintStream out) {
    ImmutableList<AbstractMap.SimpleEntry<String, NativeCallStats>> nativeCalls =
        this.nativeCalls.entrySet().stream()
            .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue().copy()))
            .collect(ImmutableList.toImmutableList());
    long totalNativeDurationNanos =
        nativeCalls.stream().mapToLong(c -> c.getValue().totalDurationNanos.get()).sum();

    long totalNativeCalls = nativeCalls.stream().mapToLong(e -> e.getValue().count.get()).sum();

    out.println();
    out.println("Total native calls: " + totalNativeCalls);
    out.println(
        "Total time spent in native calls, ms: " + totalNativeDurationNanos / 1_000_000);

    StarlarkRuntimeStatsTable.Column<AbstractMap.SimpleEntry<String, NativeCallStats>> name =
        StarlarkRuntimeStatsTable.column("name", AbstractMap.SimpleEntry::getKey);
    StarlarkRuntimeStatsTable.Column<AbstractMap.SimpleEntry<String, NativeCallStats>> count =
        StarlarkRuntimeStatsTable.column("count", e -> e.getValue().count);
    StarlarkRuntimeStatsTable.Column<AbstractMap.SimpleEntry<String, NativeCallStats>> totMs =
        StarlarkRuntimeStatsTable.column("tot_ms", e -> e.getValue().totalDurationMillis());
    StarlarkRuntimeStatsTable.Column<AbstractMap.SimpleEntry<String, NativeCallStats>> avgNs =
        StarlarkRuntimeStatsTable.column("count", e -> e.getValue().avgDurationNanos());

    out.println();
    out.println("Top " + TOP + " native calls by total duration:");
    StarlarkRuntimeStatsTable.printTableTopBy(
        out,
        nativeCalls,
        ImmutableList.of(name, totMs, avgNs, count),
        TOP,
        e -> e.getValue().totalDurationNanos.get());

    out.println();
    out.println("Top " + TOP + " native calls by count:");
    StarlarkRuntimeStatsTable.printTableTopBy(
        out,
        nativeCalls,
        ImmutableList.of(name, count, totMs, avgNs),
        TOP,
        e -> e.getValue().count.get());
  }

  private void printStarlarkCallStats(PrintStream out) {
    ImmutableList<AbstractMap.SimpleEntry<String, StarlarkCallStats>> starlarkCalls =
        this.starlarkCalls.entrySet().stream()
            .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue().copy()))
            .collect(ImmutableList.toImmutableList());

    StarlarkRuntimeStatsTable.Column<AbstractMap.SimpleEntry<String, StarlarkCallStats>> name =
        StarlarkRuntimeStatsTable.column("name", AbstractMap.SimpleEntry::getKey);
    StarlarkRuntimeStatsTable.Column<AbstractMap.SimpleEntry<String, StarlarkCallStats>> stepsTot =
        StarlarkRuntimeStatsTable.column("steps_tot", e -> e.getValue().steps);
    StarlarkRuntimeStatsTable.Column<AbstractMap.SimpleEntry<String, StarlarkCallStats>> stepsAvg =
        StarlarkRuntimeStatsTable.column("steps_avg", e -> e.getValue().avgSteps());
    StarlarkRuntimeStatsTable.Column<AbstractMap.SimpleEntry<String, StarlarkCallStats>> count =
        StarlarkRuntimeStatsTable.column("count", e -> e.getValue().count);
    StarlarkRuntimeStatsTable.Column<AbstractMap.SimpleEntry<String, StarlarkCallStats>> totMs =
        StarlarkRuntimeStatsTable.column("tot_ms", e -> e.getValue().totalDurationMillis());
    StarlarkRuntimeStatsTable.Column<AbstractMap.SimpleEntry<String, StarlarkCallStats>> avgNs =
        StarlarkRuntimeStatsTable.column("avg_ns", e -> e.getValue().avgDurationNanos());

    long totalStarlarkCalls = starlarkCalls.stream().mapToLong(e -> e.getValue().count.get()).sum();
    out.println();
    out.println("Total starlark calls: " + totalStarlarkCalls);

    out.println();
    out.println("Top " + StarlarkRuntimeStats.TOP + " starlark calls by total steps:");
    StarlarkRuntimeStatsTable.printTableTopBy(
        out,
        starlarkCalls,
        ImmutableList.of(name, stepsTot, stepsAvg, count, totMs, avgNs),
        StarlarkRuntimeStats.TOP,
        e -> e.getValue().steps.get());

    out.println();
    out.println("Top " + StarlarkRuntimeStats.TOP + " starlark calls by total duration:");
    StarlarkRuntimeStatsTable.printTableTopBy(
        out,
        starlarkCalls,
        ImmutableList.of(name, totMs, avgNs, count, stepsTot, stepsAvg),
        StarlarkRuntimeStats.TOP,
        e -> e.getValue().totalDurationNanos.get());

    out.println();
    out.println("Top " + StarlarkRuntimeStats.TOP + " starlark calls by count:");
    StarlarkRuntimeStatsTable.printTableTopBy(
        out,
        starlarkCalls,
        ImmutableList.of(name, count, stepsTot, stepsAvg, totMs, avgNs),
        StarlarkRuntimeStats.TOP,
        e -> e.getValue().count.get());
  }

  private void printCallCachedStats(PrintStream out) {
    ImmutableList<AbstractMap.SimpleEntry<String, CachedCallStats>> cachedCalls = this.cachedCalls
        .entrySet()
        .stream()
        .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue().copy()))
        .collect(ImmutableList.toImmutableList());

    int[] countByResult = new int[CallCachedResult.values().length];
    for (AbstractMap.SimpleEntry<String, CachedCallStats> call : cachedCalls) {
      for (CallCachedResult result : CallCachedResult.values()) {
        countByResult[result.ordinal()] += call.getValue().countByResult.get(result.ordinal());
      }
    }

    out.println();
    out.println(BcInstrOpcode.CALL_CACHED + " result counts:");
    StarlarkRuntimeStatsTable.printTable(
        out,
        ImmutableList.copyOf(CallCachedResult.values()),
        new String[] { "result", "count" },
        r -> new Object[] { r.name(), countByResult[r.ordinal()] });

    ImmutableList<AbstractMap.SimpleEntry<String, CachedCallStats>> topHitFunctions = cachedCalls
        .stream()
        .filter(e -> e.getValue().countByResult(CallCachedResult.HIT) != 0)
        .sorted(Comparator.comparing((AbstractMap.SimpleEntry<String, CachedCallStats> e) -> {
          return e.getValue().countByResult(CallCachedResult.HIT);
        }).reversed())
        .limit(50)
        .collect(ImmutableList.toImmutableList());

    ImmutableList<AbstractMap.SimpleEntry<String, CachedCallStats>> topSkippedFunctions = cachedCalls
        .stream()
        .filter(e -> e.getValue().countByResult(CallCachedResult.SKIP) != 0)
        .sorted(Comparator.comparing((AbstractMap.SimpleEntry<String, CachedCallStats> e) -> {
          return e.getValue().countByResult(CallCachedResult.SKIP);
        }).reversed())
        .limit(50)
        .collect(ImmutableList.toImmutableList());

    out.println();
    out.println("Top " + CallCachedResult.HIT + " functions:");
    StarlarkRuntimeStatsTable.printTable(
        out,
        topHitFunctions,
        new String[] {
            "name",
            CallCachedResult.HIT.name(),
            CallCachedResult.STORE.name(),
        },
        e -> new Object[] {
            e.getKey(),
            e.getValue().countByResult(CallCachedResult.HIT),
            e.getValue().countByResult(CallCachedResult.STORE),
        });

    out.println();
    out.println("Top " + CallCachedResult.SKIP + " functions:");
    StarlarkRuntimeStatsTable.printTable(
        out,
        topSkippedFunctions,
        new String[] {
            "name",
            CallCachedResult.SKIP.name(),
            CallCachedResult.SIDE_EFFECT.name(),
            CallCachedResult.MUTABLE.name(),
        },
        e -> new Object[] {
            e.getKey(),
            e.getValue().countByResult(CallCachedResult.SKIP),
            e.getValue().countByResult(CallCachedResult.SIDE_EFFECT),
            e.getValue().countByResult(CallCachedResult.MUTABLE),
        });
  }

  private void printInstructionStats(PrintStream out) {
    ImmutableList<AbstractMap.SimpleEntry<BcInstrOpcode, Integer>> instructionsCountByOpcode =
        Arrays.stream(BcInstrOpcode.values())
            .map(o -> new AbstractMap.SimpleEntry<>(o, this.instructions.get(o.ordinal())))
            .sorted(
                Comparator.comparing(AbstractMap.SimpleEntry<BcInstrOpcode, Integer>::getValue)
                    .reversed())
            .collect(ImmutableList.toImmutableList());

    long totalStarlarkSteps =
        instructionsCountByOpcode.stream().mapToLong(AbstractMap.SimpleEntry::getValue).sum();

    out.println();
    out.println("Total starlark instruction steps: " + totalStarlarkSteps);
    out.println();
    out.println("Instruction step count by opcode:");
    StarlarkRuntimeStatsTable.printTable(out, instructionsCountByOpcode, "opcode", "count");
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
    out.println("Total " + BcInstrOpcode.BINARY + " ops: " + totalBinaryOps);
    out.println();
    out.println("Binary ops by " + TokenKind.class.getSimpleName() + ":");
    StarlarkRuntimeStatsTable.printTable(out, binaryOpCountByOp, "bin_op", "count");
  }

  private void printDefs(PrintStream out) {
    for (String def : new ArrayList<>(defs)) {
      out.println();
      out.print(def);
    }
  }

}
