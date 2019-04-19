/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.cli;

import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.attr.InitializableFromDisk;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.util.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.event.FlushConsoleEvent;
import com.facebook.buck.io.filesystem.EmbeddedCellBuckOutInfo;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemDelegate;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.Statistics;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.buck.versions.VersionException;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.kohsuke.args4j.Option;

/**
 * This is the core of our perf commands, it handles the outer prepare + loop and statistics
 * gathering.
 */
public abstract class AbstractPerfCommand<CommandContext> extends AbstractCommand {

  @Option(name = "--repeat", usage = "controls how many times to run the computation.")
  private int repeat = 1;

  @Option(name = "--ignore-first", usage = "ignores the first n runs for the computed statistics.")
  private int ignoreCount = 0;

  @Option(
      name = "--stop-after-seconds",
      usage =
          "configures a time after which the command will exit (pending any executing computations), regardless of the --repeat paramater.")
  private int exitDuration = Integer.MAX_VALUE;

  @Option(
      name = "--force-gc-between-runs",
      usage =
          "calls System.gc() between each run. This can reduce the variance, but may also hide the gc cost of the thing we are measuring.")
  private boolean forceGcBetweenRuns = false;

  /** Result of invocation of the targeted perf test. */
  interface PerfResult {
    // TODO(cjhopman): Do something with this or delete it.
  }

  protected abstract String getComputationName();

  /**
   * Heavyweight setup can be done in prepareTest. The returned context will be provided in each
   * runPerfTest call.
   */
  abstract CommandContext prepareTest(CommandRunnerParams params) throws Exception;

  /** Run the targeted test. */
  abstract void runPerfTest(CommandRunnerParams params, CommandContext context) throws Exception;

  @Override
  public final ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    CommandContext context = prepareTest(params);

    Stopwatch stopwatch = Stopwatch.createStarted();
    Stopwatch current = Stopwatch.createStarted();

    Statistics statistics = new Statistics();

    int count = 0;

    for (int i = 0; i < repeat; i++) {
      if (i != 0 && exitDuration > 0 && stopwatch.elapsed().getSeconds() >= exitDuration) {
        printWarning(params, "Exiting after %d cycles due to reaching requested duration.", i);
        break;
      }

      boolean ignore = i < ignoreCount;

      printWarning(
          params,
          "Beginning computation %d%s.%s",
          i + 1,
          ignore ? "(ignored)" : "",
          i == 0
              ? ""
              : String.format(
                  " Current duration %.02f sec, total duration %.02f sec%s.",
                  current.elapsed().toMillis() / 1000.,
                  stopwatch.elapsed().toMillis() / 1000.,
                  exitDuration == 0 ? "" : String.format(" (max duration %s sec)", exitDuration)));

      if (forceGcBetweenRuns) {
        System.gc();
      }
      current.reset().start();
      runPerfTest(params, context);
      if (!ignore) {
        statistics.addValue(current.elapsed().toMillis());
      }
      count++;
    }

    stopwatch.stop();
    params.getBuckEventBus().post(new FlushConsoleEvent());

    PrintStream out = params.getConsole().getStdOut();
    out.printf(
        "Running %d %s computations took %.03f sec\n",
        count, getComputationName(), (stopwatch.elapsed().toMillis() / 1000.));

    if (count > 1 && statistics.getN() > 1) {
      double mean = statistics.getMean() / 1000.;
      double off = statistics.getConfidenceIntervalOffset() / 1000.;
      out.printf(
          "Recorded %d statistics. Mean %.03f sec [%.03f, %.03f].\n",
          statistics.getN(), mean, mean - off, mean + off);
    }

    // TODO(cjhopman): Do something with PerfResult
    return ExitCode.SUCCESS;
  }

  /** Most of our perf tests require a target graph, this helps them get it concisely. */
  protected TargetGraph getTargetGraph(
      CommandRunnerParams params, ImmutableSet<BuildTarget> targets)
      throws InterruptedException, IOException, VersionException {
    TargetGraph targetGraph;
    try (CommandThreadManager pool =
        new CommandThreadManager("Perf", getConcurrencyLimit(params.getBuckConfig()))) {
      targetGraph =
          params
              .getParser()
              .buildTargetGraph(
                  createParsingContext(params.getCell(), pool.getListeningExecutorService()),
                  targets);
    } catch (BuildFileParseException e) {
      throw new BuckUncheckedExecutionException(e);
    }
    if (params.getBuckConfig().getView(BuildBuckConfig.class).getBuildVersions()) {
      targetGraph =
          toVersionedTargetGraph(params, TargetGraphAndBuildTargets.of(targetGraph, targets))
              .getTargetGraph();
    }
    return targetGraph;
  }

  /** Calls initializeFromDisk() on all the initializable rules in the graph. This is unsafe. */
  protected static void initializeRulesFromDisk(
      ActionGraphBuilder graphBuilder, ImmutableList<BuildRule> rulesInGraph) throws IOException {
    for (BuildRule rule : rulesInGraph) {
      if (rule instanceof InitializableFromDisk) {
        ((InitializableFromDisk<?>) rule)
            .initializeFromDisk(
                DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder)));
      }
    }
  }

  /** Gets a list of the rules in the graph reachable from the provided targets. */
  protected static ImmutableList<BuildRule> getRulesInGraph(
      ActionGraphBuilder graphBuilder, Iterable<BuildTarget> targets)
      throws AcyclicDepthFirstPostOrderTraversal.CycleException {
    ImmutableList.Builder<BuildRule> rulesBuilder = ImmutableList.builder();

    ImmutableSortedSet<BuildRule> topLevelRules = graphBuilder.requireAllRules(targets);

    rulesBuilder.addAll(
        new AcyclicDepthFirstPostOrderTraversal<BuildRule>(
                node -> {
                  ImmutableList.Builder<BuildRule> depsBuilder = ImmutableList.builder();
                  // When running `buck build foo`, the runtime deps of referenced rules will be
                  // part of the build graph and so we should include them in these tests to better
                  // reflect reality.
                  if (node instanceof HasRuntimeDeps) {
                    ((HasRuntimeDeps) node)
                        .getRuntimeDeps(new SourcePathRuleFinder(graphBuilder))
                        .map(graphBuilder::getRule)
                        .forEach(depsBuilder::add);
                  }
                  depsBuilder.addAll(node.getBuildDeps());
                  return depsBuilder.build().iterator();
                })
            .traverse(topLevelRules));

    return rulesBuilder.build();
  }

  /**
   * Creates a {@link StackedFileHashCache} similar to a real command but that uses our hash-faking
   * delegate.
   */
  protected StackedFileHashCache createStackedFileHashCache(CommandRunnerParams params)
      throws InterruptedException {
    FileHashCacheMode cacheMode =
        params.getCell().getBuckConfig().getView(BuildBuckConfig.class).getFileHashCacheMode();

    return new StackedFileHashCache(
        ImmutableList.<ProjectFileHashCache>builder()
            .addAll(
                params.getCell().getAllCells().stream()
                    .flatMap(this::createFileHashCaches)
                    .collect(Collectors.toList()))
            .addAll(
                DefaultFileHashCache.createOsRootDirectoriesCaches(
                    new ProjectFilesystemFactory() {
                      @Override
                      public ProjectFilesystem createProjectFilesystem(
                          Path root,
                          Config config,
                          Optional<EmbeddedCellBuckOutInfo> embeddedCellBuckOutInfo) {
                        return createHashFakingFilesystem(
                            new DefaultProjectFilesystemFactory()
                                .createProjectFilesystem(root, config, embeddedCellBuckOutInfo));
                      }

                      @Override
                      public ProjectFilesystem createProjectFilesystem(Path root, Config config) {
                        return createProjectFilesystem(root, config, Optional.empty());
                      }

                      @Override
                      public ProjectFilesystem createProjectFilesystem(Path root) {
                        return createProjectFilesystem(root, new Config());
                      }

                      @Override
                      public ProjectFilesystem createOrThrow(Path path) {
                        return createProjectFilesystem(path);
                      }
                    },
                    cacheMode))
            .build());
  }

  private Stream<? extends ProjectFileHashCache> createFileHashCaches(Cell cell) {
    ProjectFilesystem realFilesystem = cell.getFilesystem();
    // Just use the root cell's mode.
    FileHashCacheMode cacheMode =
        cell.getBuckConfig().getView(BuildBuckConfig.class).getFileHashCacheMode();
    ProjectFilesystem hashFakingFilesystem = createHashFakingFilesystem(realFilesystem);

    return Stream.of(
        DefaultFileHashCache.createBuckOutFileHashCache(hashFakingFilesystem, cacheMode),
        DefaultFileHashCache.createDefaultFileHashCache(hashFakingFilesystem, cacheMode));
  }

  private DefaultProjectFilesystem createHashFakingFilesystem(ProjectFilesystem realFilesystem) {
    HashFunction hashFunction = Hashing.sha1();

    return new DefaultProjectFilesystem(
        realFilesystem.getRootPath(),
        realFilesystem.getBlacklistedPaths(),
        realFilesystem.getBuckPaths(),
        new ProjectFilesystemDelegate() {
          @Override
          public Sha1HashCode computeSha1(Path pathRelativeToProjectRootOrJustAbsolute) {
            return Sha1HashCode.fromHashCode(
                hashFunction
                    .newHasher()
                    .putUnencodedChars(pathRelativeToProjectRootOrJustAbsolute.toString())
                    .hash());
          }

          @Override
          public Path getPathForRelativePath(Path pathRelativeToProjectRoot) {
            return realFilesystem.getPathForRelativePath(pathRelativeToProjectRoot);
          }

          @Override
          public boolean isExecutable(Path child) {
            return false;
          }

          @Override
          public boolean isSymlink(Path path) {
            return false;
          }

          @Override
          public boolean exists(Path pathRelativeToProjectRoot, LinkOption... options) {
            return true;
          }

          @Override
          public ImmutableMap<String, ?> getDetailsForLogging() {
            return ImmutableMap.of();
          }
        },
        DefaultProjectFilesystemFactory.getWindowsFSInstance());
  }

  @Override
  public final boolean isReadOnly() {
    return true;
  }
}
