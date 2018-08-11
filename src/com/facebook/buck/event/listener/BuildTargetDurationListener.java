/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.event.listener;

import com.facebook.buck.core.build.event.BuildEvent.RuleCountCalculated;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.build.event.BuildRuleEvent.BeginningBuildRuleEvent;
import com.facebook.buck.core.build.event.BuildRuleEvent.EndingBuildRuleEvent;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.actiongraph.ActionGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.util.graph.AbstractBottomUpTraversal;
import com.facebook.buck.core.util.graph.TraversableGraph;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.chrome_trace.ChromeTraceEvent;
import com.facebook.buck.event.chrome_trace.ChromeTraceEvent.Phase;
import com.facebook.buck.event.chrome_trace.ChromeTraceWriter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.MinMaxPriorityQueue;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.SimpleTimeLimiter;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.immutables.value.Value;

/**
 * Listens {@link ActionGraphEvent.Finished} to get {@link ActionGraph}, {@link BuildRuleEvent} to
 * trace {@link BuildRule} to compute critical path for test targets.
 */
public class BuildTargetDurationListener implements BuckEventListener {

  private static final Logger LOG = Logger.get(BuildTargetDurationListener.class);

  public static final String CRITICAL_PATH_FILE_NAME = "critical-path";
  public static final String CRITICAL_PATH_TRACE_FILE_NAME = "critical-path.trace";
  public static final String TARGETS_BUILD_TIMES_FILE_NAME = "targets-build-times";

  private static final int SHUTDOWN_TIMEOUT_SECONDS = 60;

  private final InvocationInfo info;
  private final ExecutorService executor;
  private final ProjectFilesystem filesystem;
  private final int criticalPathCount;

  private Optional<ActionGraph> actionGraph = Optional.empty();
  private Optional<ImmutableSet<BuildTarget>> targetBuildRules = Optional.empty();

  @VisibleForTesting
  ConcurrentMap<String, BuildRuleInfo> getBuildRuleInfos() {
    return buildRuleInfos;
  }

  // Hold the latest one for the target
  private ConcurrentMap<String, BuildRuleInfo> buildRuleInfos = Maps.newConcurrentMap();

  public BuildTargetDurationListener(
      InvocationInfo info,
      ProjectFilesystem filesystem,
      ExecutorService executor,
      int criticalPathCount) {
    this.info = info;
    this.filesystem = filesystem;
    this.executor = executor;
    this.criticalPathCount = criticalPathCount;
  }

  /** Save start of the {@link BuildRuleEvent}. */
  @Subscribe
  public void buildRuleEventStarted(BuildRuleEvent.Started event) {
    final String buildRuleName = event.getBuildRule().getFullyQualifiedName();
    final long startEpochMillis = event.getTimestamp();
    Preconditions.checkState(
        !buildRuleInfos.containsKey(buildRuleName),
        "There can not be more than one BuildRuleEvent.Started for the %s BuildRule.",
        buildRuleName);
    LOG.info("BuildRuleEventStarted{name=%s, time=%d}", buildRuleName, startEpochMillis);
    BuildRuleInfo ruleInfo =
        new BuildRuleInfo(buildRuleName, criticalPathCount, startEpochMillis, startEpochMillis);
    buildRuleInfos.put(buildRuleName, ruleInfo);
    event
        .getBuildRule()
        .getBuildDeps()
        .stream()
        .map(BuildRule::getFullyQualifiedName)
        .forEach(ruleInfo::addDependency);
  }

  /**
   * Save end of the {@link BuildRuleEvent} for {@link
   * com.facebook.buck.event.RuleKeyCalculationEvent}.
   */
  @Subscribe
  public synchronized void buildRuleEventSuspended(BuildRuleEvent.Suspended event) {
    LOG.info(
        "BuildRuleEventSuspended{name=%s, time=%d}",
        event.getBuildRule().getFullyQualifiedName(), event.getTimestamp());
    saveEndOfBuildRuleEvent(
        event.getBuildRule().getFullyQualifiedName(), event.getBeginningEvent(), event);
  }

  /** Save end of the {@link BuildRuleEvent}. */
  @Subscribe
  public synchronized void buildRuleEventFinished(BuildRuleEvent.Finished event) {
    LOG.info(
        "BuildRuleEventFinished{name=%s, time=%d}",
        event.getBuildRule().getFullyQualifiedName(), event.getTimestamp());
    saveEndOfBuildRuleEvent(
        event.getBuildRule().getFullyQualifiedName(), event.getBeginningEvent(), event);
  }

  /** Update end of the {@link BuildRuleEvent}. */
  private void saveEndOfBuildRuleEvent(
      String buildRuleName, BeginningBuildRuleEvent begin, EndingBuildRuleEvent end) {
    Preconditions.checkState(
        buildRuleInfos.containsKey(buildRuleName),
        "First, a BuildRuleEvent %s has to start in order to finish.",
        buildRuleName);
    BuildRuleInfo currentBuildRuleInfo = buildRuleInfos.get(buildRuleName);
    currentBuildRuleInfo.updateDuration(end.getDuration().getWallMillisDuration());
    currentBuildRuleInfo.updateFinish(end.getTimestamp());
    currentBuildRuleInfo.addBuildRuleEventInterval(
        TimeUnit.NANOSECONDS.toMicros(begin.getNanoTime()),
        TimeUnit.NANOSECONDS.toMicros(end.getNanoTime()));
  }

  /** Save the {@link ActionGraph} */
  @Subscribe
  public synchronized void actionGraphFinished(ActionGraphEvent.Finished finished) {
    actionGraph = finished.getActionGraph();
  }

  @Subscribe
  public synchronized void ruleCountCalculated(RuleCountCalculated ruleCountCalculated) {
    targetBuildRules = Optional.of(ruleCountCalculated.getBuildRules());
  }

  private Path getLogFilePath() {
    return filesystem.resolve(info.getBuckLogDir()).resolve(CRITICAL_PATH_FILE_NAME);
  }

  private Path getTraceFilePath() {
    return filesystem.resolve(info.getBuckLogDir()).resolve(CRITICAL_PATH_TRACE_FILE_NAME);
  }

  private Path getTargetFilePath() {
    return filesystem.resolve(info.getBuckLogDir()).resolve(TARGETS_BUILD_TIMES_FILE_NAME);
  }

  @VisibleForTesting
  static ImmutableList<ImmutableBuildRuleCriticalPath> constructBuildRuleCriticalPaths(
      List<Deque<CriticalPathEntry>> criticalPaths) {
    ImmutableList.Builder<ImmutableBuildRuleCriticalPath> criticalPathsBuilder =
        ImmutableList.builder();
    for (Deque<CriticalPathEntry> path : criticalPaths) {
      CriticalPathEntry last = path.getLast();
      criticalPathsBuilder.add(
          ImmutableBuildRuleCriticalPath.of(
              last.ruleName(), last.wholeDuration(), path, last.longest()));
    }
    return criticalPathsBuilder.build();
  }

  @VisibleForTesting
  static ImmutableList<Deque<CriticalPathEntry>> constructCriticalPaths(
      Collection<BuildRuleInfo> rootBuildRuleInfos, int k) {
    MinMaxPriorityQueue<BuildRuleInfoSelectedChain> topK = findCriticalNodes(rootBuildRuleInfos, k);
    Map<BuildRuleInfo, Set<BuildRuleInfo.Chain>> usedChainsMap = Maps.newHashMap();
    ImmutableList.Builder<Deque<CriticalPathEntry>> criticalPaths = ImmutableList.builder();
    while (!topK.isEmpty()) {
      BuildRuleInfoSelectedChain root = topK.poll();
      Set<BuildRuleInfo.Chain> usedChains =
          usedChainsMap.getOrDefault(root.buildRuleInfo(), Sets.newHashSet());
      criticalPaths.add(criticalPath(root.chain(), root.buildRuleInfo(), usedChains));
    }
    return criticalPaths.build();
  }

  private void rendersCriticalPaths(List<Deque<CriticalPathEntry>> criticalPaths)
      throws IOException {
    try (FileOutputStream fos = new FileOutputStream(getTraceFilePath().toFile())) {
      rendersCriticalPathsTraceFile(criticalPaths, fos);
    }
  }

  @VisibleForTesting
  static void rendersCriticalPathsTraceFile(
      List<Deque<CriticalPathEntry>> criticalPaths, OutputStream os) {
    try (ChromeTraceWriter traceWriter = new ChromeTraceWriter(os)) {
      traceWriter.writeStart();
      traceWriter.writeEvent(
          new ChromeTraceEvent(
              "buck",
              "process_name",
              Phase.METADATA,
              0,
              0,
              0,
              0,
              ImmutableMap.of("name", "Critical Path")));
      rendersCriticalPaths(criticalPaths, traceWriter);
      traceWriter.writeEnd();
    } catch (IOException e) {
      LOG.error(e, "Could not write critical path to trace file.");
    }
  }

  private static void rendersCriticalPaths(
      List<Deque<CriticalPathEntry>> criticalPaths, ChromeTraceWriter traceWriter)
      throws IOException {
    int threadId = 0;
    for (Deque<CriticalPathEntry> path : criticalPaths) {
      for (CriticalPathEntry criticalPathEntry : path) {
        rendersCriticalPathEntry(criticalPathEntry, traceWriter, threadId);
      }
      ++threadId;
    }
  }

  /**
   * Renders rule in a similar way to {@link
   * com.facebook.buck.distributed.build_slave.DistBuildChromeTraceRenderer#renderRule}.
   */
  private static void rendersCriticalPathEntry(
      CriticalPathEntry criticalPathEntry, ChromeTraceWriter traceWriter, int tid)
      throws IOException {
    for (BuildRuleEventInterval interval : criticalPathEntry.intervals()) {
      long end = interval.end() > interval.start() ? interval.end() : interval.start() + 1;
      ImmutableMap<String, String> startArgs =
          ImmutableMap.of(
              "critical_blocking_rule",
              criticalPathEntry.prev().map(rule -> rule.ruleName).orElse("None"),
              "length_of_critical_path",
              String.format("%dms", criticalPathEntry.longest()));
      traceWriter.writeEvent(
          new ChromeTraceEvent(
              "buck",
              criticalPathEntry.ruleName(),
              Phase.BEGIN,
              0,
              tid,
              interval.start(),
              0,
              startArgs));
      traceWriter.writeEvent(
          new ChromeTraceEvent(
              "buck", criticalPathEntry.ruleName(), Phase.END, 0, tid, end, 0, ImmutableMap.of()));
    }
  }

  @Override
  public synchronized void close() {
    SimpleTimeLimiter timeLimiter = SimpleTimeLimiter.create(executor);
    try {
      timeLimiter.runWithTimeout(
          () -> {
            try (BufferedOutputStream outputStream =
                    new BufferedOutputStream(new FileOutputStream(getLogFilePath().toFile()));
                BufferedOutputStream targetStream =
                    new BufferedOutputStream(new FileOutputStream(getTargetFilePath().toFile()))) {
              LOG.info("Starting critical path calculation for %s", info);
              LOG.info("Writing critical path to %s", getLogFilePath().toString());
              if (actionGraph.isPresent()) {
                updateBuildRuleInfosWithDependents();
                computeCriticalPathsUsingGraphTraversal(buildRuleInfos);
                // Find root build rules
                Collection<BuildRuleInfo> rootBuildRuleInfos = Lists.newArrayList();
                if (targetBuildRules.isPresent()) {
                  for (BuildTarget buildTarget : targetBuildRules.get()) {
                    BuildRuleInfo buildRuleInfo =
                        buildRuleInfos.get(buildTarget.getFullyQualifiedName());
                    if (buildRuleInfo != null) {
                      rootBuildRuleInfos.add(buildRuleInfo);
                    } else {
                      LOG.warn(
                          "Could not find build rule for the target %s!",
                          buildTarget.getFullyQualifiedName());
                    }
                  }
                } else {
                  LOG.warn(
                      "There were not any target, construction of critical paths will be skipped.");
                }
                List<Deque<CriticalPathEntry>> kCriticalPaths =
                    constructCriticalPaths(rootBuildRuleInfos, criticalPathCount);
                // Render chrome trace of the critical path of the longest
                rendersCriticalPaths(kCriticalPaths);
                // Serialize BuildRuleCriticalPaths
                ObjectMappers.WRITER.writeValue(
                    outputStream, constructBuildRuleCriticalPaths(kCriticalPaths));
                // Serialize BuildTargetResults
                ObjectMappers.WRITER.writeValue(
                    targetStream, constructBuildTargetResults(rootBuildRuleInfos));
                LOG.info("Critical path and target results have been written successfully.");
              } else {
                ObjectMappers.WRITER.writeValue(outputStream, Collections.emptyList());
                LOG.warn("There was no action graph, computation is skipped.");
              }
            } catch (IOException e) {
              LOG.warn(e, "Could not complete IO Request.");
            }
          },
          SHUTDOWN_TIMEOUT_SECONDS,
          TimeUnit.SECONDS);
    } catch (InterruptedException | TimeoutException e) {
      LOG.error(e, "Could not complete all jobs within timeout during shutdown.");
    } catch (AssertionError e) {
      LOG.error(e.getCause(), "An exception occurred during execution of task.");
    } finally {
      executor.shutdownNow();
    }
  }

  private void updateBuildRuleInfosWithDependents() {
    if (!actionGraph.isPresent()) {
      return;
    }
    for (BuildRule buildRule : actionGraph.get().getNodes()) {
      // for each dependency include a back-edge from dependency to dependent
      buildRule
          .getBuildDeps()
          .forEach(
              dependency -> {
                BuildRuleInfo dependencyBuildRuleInfo =
                    buildRuleInfos.get(dependency.getFullyQualifiedName());
                // It might not exist if a BuildRuleEvent is not executed!
                if (dependencyBuildRuleInfo != null) {
                  dependencyBuildRuleInfo.addDependent(buildRule.getFullyQualifiedName());
                }
              });
    }
  }

  /** Construct {@link BuildTargetResult} list for all given roots. */
  @VisibleForTesting
  static ImmutableList<ImmutableBuildTargetResult> constructBuildTargetResults(
      Collection<BuildRuleInfo> rootBuildRuleInfos) {
    ImmutableList.Builder<ImmutableBuildTargetResult> buildTargetResults = ImmutableList.builder();
    for (BuildRuleInfo buildRuleInfo : rootBuildRuleInfos) {
      buildTargetResults.add(
          ImmutableBuildTargetResult.of(
              buildRuleInfo.ruleName, buildRuleInfo.getWholeTargetDuration()));
    }
    return buildTargetResults.build();
  }

  /** Modifies {@link BuildRuleInfo#chain} to save the longest paths. */
  @VisibleForTesting
  static BuildRuleInfoGraph computeCriticalPathsUsingGraphTraversal(
      Map<String, BuildRuleInfo> buildRuleInfos) throws IOException {
    BuildRuleInfoGraph graph = new BuildRuleInfoGraph(buildRuleInfos);
    for (BuildRuleInfo buildRuleInfo : graph.getNodesWithNoOutgoingEdges()) {
      buildRuleInfo.chain.add(
          new BuildRuleInfo.Chain(buildRuleInfo.getDuration(), Optional.empty()));
    }
    new BuildRuleInfoGraphTraversal(graph).traverse();
    return graph;
  }

  /** Finds top K critical nodes and corresponding chain for the critical paths. */
  @VisibleForTesting
  static MinMaxPriorityQueue<BuildRuleInfoSelectedChain> findCriticalNodes(
      Iterable<BuildRuleInfo> rootRules, int k) {
    MinMaxPriorityQueue<BuildRuleInfoSelectedChain> topK =
        MinMaxPriorityQueue.orderedBy(
                (BuildRuleInfoSelectedChain first, BuildRuleInfoSelectedChain second) ->
                    Long.compare(second.chain().longest, first.chain().longest))
            .maximumSize(k)
            .create();
    for (BuildRuleInfo ruleInfo : rootRules) {
      for (BuildRuleInfo.Chain chain : ruleInfo.chain) {
        topK.add(ImmutableBuildRuleInfoSelectedChain.of(ruleInfo, chain));
      }
    }
    return topK;
  }

  /**
   * Construct the critical path to the target coming from the given chain. usedChain prevents
   * duplicate paths.
   */
  @VisibleForTesting
  static Deque<CriticalPathEntry> criticalPath(
      BuildRuleInfo.Chain chain, BuildRuleInfo target, Set<BuildRuleInfo.Chain> usedChains) {
    Deque<CriticalPathEntry> path = Queues.newArrayDeque();
    path.push(
        ImmutableCriticalPathEntry.of(
            target.ruleName,
            target.startEpochMillis,
            target.finishEpochMillis,
            target.getDuration(),
            target.getWholeTargetDuration(),
            chain.longest,
            chain.prev,
            target.intervals));
    long longest = chain.longest;
    Optional<BuildRuleInfo> prev = chain.prev;
    while (prev.isPresent()) {
      chain = null;
      longest -= target.getDuration();
      target = prev.get();
      /** Search for previous {@link BuildRuleInfo} matching longest and not selected before. */
      for (BuildRuleInfo.Chain c : target.chain) {
        if (c.longest == longest && !usedChains.contains(c)) {
          chain = c;
          usedChains.add(c);
          break;
        }
      }
      prev = chain != null ? chain.prev : Optional.empty();
      longest = chain != null ? chain.longest : 0L;
      path.push(
          ImmutableCriticalPathEntry.of(
              target.ruleName,
              target.startEpochMillis,
              target.finishEpochMillis,
              target.getDuration(),
              target.getWholeTargetDuration(),
              longest,
              prev,
              target.intervals));
    }
    return path;
  }

  /** Class extending {@link AbstractBottomUpTraversal} to do traversal bottom up. */
  public static class BuildRuleInfoGraphTraversal
      extends AbstractBottomUpTraversal<BuildRuleInfo, IOException> {

    private BuildRuleInfoGraph graph;

    public BuildRuleInfoGraphTraversal(BuildRuleInfoGraph graph) {
      super(graph);
      this.graph = graph;
    }

    @Override
    public void visit(BuildRuleInfo currentBuildRuleInfo) {
      for (BuildRuleInfo neigborBuildRuleInfo : graph.getOutgoingNodesFor(currentBuildRuleInfo)) {
        for (BuildRuleInfo.Chain c : neigborBuildRuleInfo.chain) {
          currentBuildRuleInfo.chain.add(
              new BuildRuleInfo.Chain(
                  c.longest + currentBuildRuleInfo.getDuration(),
                  Optional.of(neigborBuildRuleInfo)));
        }
      }
    }
  }

  /** Implementing {@link TraversableGraph} interface. */
  public static class BuildRuleInfoGraph implements TraversableGraph<BuildRuleInfo> {

    private Map<String, BuildRuleInfo> buildRuleInfos;

    public BuildRuleInfoGraph(Map<String, BuildRuleInfo> buildRuleInfos) {
      this.buildRuleInfos = buildRuleInfos;
    }

    @Override
    public Iterable<BuildRuleInfo> getNodesWithNoIncomingEdges() {
      return getBuildRuleInfos()
          .stream()
          .filter(buildRuleInfo -> buildRuleInfo.dependents.isEmpty())
          .collect(Collectors.toList());
    }

    @Override
    public Iterable<BuildRuleInfo> getNodesWithNoOutgoingEdges() {
      return getBuildRuleInfos()
          .stream()
          .filter(buildRuleInfo -> buildRuleInfo.dependencies.isEmpty())
          .collect(Collectors.toList());
    }

    /** Ignoring nodes, they are not in buildRuleInfos, since not received a start event for them */
    @Override
    public Iterable<BuildRuleInfo> getIncomingNodesFor(BuildRuleInfo sink) {
      return sink.dependents
          .stream()
          .filter(buildRuleInfos::containsKey) // Ignoring Rules without Start event
          .map(buildRuleInfos::get)
          .collect(Collectors.toList());
    }

    /** Ignoring nodes, they are not in buildRuleInfos, since not received a start event for them */
    @Override
    public Iterable<BuildRuleInfo> getOutgoingNodesFor(BuildRuleInfo source) {
      return source
          .dependencies
          .stream()
          .filter(buildRuleInfos::containsKey) // Ignoring Rules without Start event
          .map(buildRuleInfos::get)
          .collect(Collectors.toList());
    }

    @Override
    public Iterable<BuildRuleInfo> getNodes() {
      return getBuildRuleInfos();
    }

    private Collection<BuildRuleInfo> getBuildRuleInfos() {
      return buildRuleInfos.values();
    }
  }

  /** An entry in the critical path of the target. */
  @Value.Immutable(copy = false, builder = false)
  @JsonSerialize(as = ImmutableCriticalPathEntry.class)
  interface CriticalPathEntry {
    @JsonProperty("rule-name")
    @Value.Parameter
    String ruleName();

    @JsonProperty("start")
    @Value.Parameter
    long startTimestamp();

    @JsonProperty("finish")
    @Value.Parameter
    long finishTimestamp();

    @JsonProperty("duration")
    @Value.Parameter
    long duration();

    @Value.Parameter
    @JsonIgnore
    long wholeDuration();

    @Value.Parameter
    @JsonIgnore
    long longest();

    @Value.Parameter
    @JsonIgnore
    Optional<BuildRuleInfo> prev();

    @Value.Parameter
    @JsonIgnore
    List<ImmutableBuildRuleEventInterval> intervals();
  }

  /** Used to keep top k critical paths, and serialize as json. */
  @Value.Immutable(copy = false, builder = false)
  @JsonSerialize(as = ImmutableBuildRuleCriticalPath.class)
  interface BuildRuleCriticalPath {
    @JsonProperty("target-name")
    @Value.Parameter
    String targetName();

    @JsonProperty("target-duration")
    @Value.Parameter
    long targetDuration();

    @JsonProperty("critical-path")
    @Value.Parameter
    ImmutableList<CriticalPathEntry> criticalPath();

    @JsonProperty("critical-path-duration")
    @Value.Parameter
    long criticalPathDuration();
  }

  /** Used to keep duration for each test target, and serialize as json. */
  @Value.Immutable(copy = false, builder = false)
  @JsonSerialize(as = ImmutableBuildTargetResult.class)
  interface BuildTargetResult {
    @JsonProperty("target-name")
    @Value.Parameter
    String targetName();

    @JsonProperty("target-duration")
    @Value.Parameter
    long targetDuration();
  }

  /**
   * An entry to hold selected {@link BuildRuleInfo.Chain} chain from k chains of a {@link
   * BuildRuleInfo}.
   */
  @Value.Immutable(copy = false, builder = false)
  interface BuildRuleInfoSelectedChain {
    @Value.Parameter
    BuildRuleInfo buildRuleInfo();

    @Value.Parameter
    BuildRuleInfo.Chain chain();
  }

  /** An entry to hold interval of {@link BuildRuleEvent}. */
  @Value.Immutable(copy = false, builder = false)
  interface BuildRuleEventInterval {
    @Value.Parameter
    long start();

    @Value.Parameter
    long end();
  }

  /**
   * Similar to {@link com.facebook.buck.distributed.build_slave.DistBuildTrace.RuleTrace} keeps
   * information regarding {@link BuildRule}. Start, End times and dependents, dependencies of this
   * {@link BuildRule}.
   */
  public static class BuildRuleInfo {
    private final String ruleName;
    private final long startEpochMillis;
    private long finishEpochMillis;
    private long duration;
    private final Set<String> dependents;
    private final Set<String> dependencies;
    private final List<ImmutableBuildRuleEventInterval> intervals;

    private MinMaxPriorityQueue<Chain> chain;

    /**
     * Keeps a pointer to previous {@link BuildRuleInfo} and its time. Implements {@link Comparable}
     * interface in natural order.
     */
    static class Chain implements Comparable<Chain> {
      private final long longest;
      private final Optional<BuildRuleInfo> prev;

      public Chain(long longest, Optional<BuildRuleInfo> prev) {
        this.longest = longest;
        this.prev = prev;
      }

      public long getLongest() {
        return longest;
      }

      public Optional<BuildRuleInfo> getPrev() {
        return prev;
      }

      @Override
      public int compareTo(Chain other) {
        return Long.compare(this.longest, other.longest);
      }

      @Override
      public String toString() {
        return "Chain{"
            + "longest="
            + longest
            + ", prev="
            + (prev.isPresent() ? prev.get() : "none")
            + '}';
      }
    }

    public BuildRuleInfo(String ruleName, int k, long startEpochMillis, long finishEpochMillis) {
      this.ruleName = ruleName;
      this.startEpochMillis = startEpochMillis;
      this.finishEpochMillis = finishEpochMillis;
      this.duration = 0;
      this.dependents = Sets.newHashSet();
      this.dependencies = Sets.newHashSet();
      this.chain =
          MinMaxPriorityQueue.orderedBy(Collections.reverseOrder()).maximumSize(k).create();
      this.intervals = Lists.newArrayList();
    }

    public void addDependent(String dependent) {
      this.dependents.add(dependent);
    }

    public boolean noDependent() {
      return this.dependents.isEmpty();
    }

    public void addDependency(String dependency) {
      this.dependencies.add(dependency);
    }

    public void updateFinish(long finishEpochMillis) {
      this.finishEpochMillis = Math.max(finishEpochMillis, this.finishEpochMillis);
    }

    public void addBuildRuleEventInterval(long startEpochMicros, long finishEpochMicros) {
      this.intervals.add(ImmutableBuildRuleEventInterval.of(startEpochMicros, finishEpochMicros));
    }

    public void updateDuration(long duration) {
      this.duration += duration;
    }

    public long getDuration() {
      return duration;
    }

    public long getWholeTargetDuration() {
      return this.finishEpochMillis - this.startEpochMillis;
    }

    public String getRuleName() {
      return ruleName;
    }

    public MinMaxPriorityQueue<Chain> getChain() {
      return chain;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      BuildRuleInfo that = (BuildRuleInfo) o;
      return Objects.equal(ruleName, that.ruleName);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(ruleName);
    }

    @Override
    public String toString() {
      return "BuildRuleInfo{" + "ruleName='" + ruleName + '\'' + '}';
    }
  }
}
