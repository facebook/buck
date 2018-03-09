/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.resources.ResourcesConfig;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.log.LogConfigSetup;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.BuildTargetPatternTargetNodeParser;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.rules.CellConfig;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.RelativeCellName;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.keys.DefaultRuleKeyCache;
import com.facebook.buck.rules.keys.EventPostingRuleKeyCacheScope;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.rules.keys.RuleKeyCacheScope;
import com.facebook.buck.rules.keys.TrackedRuleKeyCache;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.util.CloseableMemoizedSupplier;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.cache.InstrumentingCacheStatsTracker;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.versions.VersionException;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.Closeable;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Option;

public abstract class AbstractCommand implements Command {

  private static final String HELP_LONG_ARG = "--help";
  private static final String NO_CACHE_LONG_ARG = "--no-cache";
  private static final String OUTPUT_TEST_EVENTS_TO_FILE_LONG_ARG = "--output-test-events-to-file";
  private static final String PROFILE_PARSER_LONG_ARG = "--profile-buck-parser";
  private static final String NUM_THREADS_LONG_ARG = "--num-threads";

  /**
   * This value should never be read. {@link VerbosityParser} should be used instead. args4j
   * requires that all options that could be passed in are listed as fields, so we include this
   * field so that {@code --verbose} is universally available to all commands.
   */
  @Option(
    name = VerbosityParser.VERBOSE_LONG_ARG,
    aliases = {VerbosityParser.VERBOSE_SHORT_ARG},
    usage = "Specify a number between 0 and 8. '-v 1' is default, '-v 8' is most verbose."
  )
  @SuppressWarnings("PMD.UnusedPrivateField")
  private int verbosityLevel = -1;

  private volatile ExecutionContext executionContext;

  @Option(name = NUM_THREADS_LONG_ARG, aliases = "-j", usage = "Default is 1.25 * num processors.")
  @Nullable
  private Integer numThreads = null;

  @Option(
    name = "--config",
    aliases = {"-c"},
    usage = ""
  )
  private Map<String, String> configOverrides = new LinkedHashMap<>();

  @Override
  public CellConfig getConfigOverrides() {
    CellConfig.Builder builder = CellConfig.builder();

    // Parse command-line config overrides.
    for (Map.Entry<String, String> entry : configOverrides.entrySet()) {
      List<String> key = Splitter.on("//").limit(2).splitToList(entry.getKey());
      RelativeCellName cellName = RelativeCellName.ALL_CELLS_SPECIAL_NAME;
      String configKey = key.get(0);
      if (key.size() == 2) {
        // Here we explicitly take the whole string as the cell name. We don't support transitive
        // path overrides for cells.
        if (key.get(0).length() == 0) {
          cellName = RelativeCellName.ROOT_CELL_NAME;
        } else {
          cellName = RelativeCellName.of(ImmutableSet.of(key.get(0)));
        }
        configKey = key.get(1);
      }
      int separatorIndex = configKey.lastIndexOf('.');
      if (separatorIndex < 0 || separatorIndex == configKey.length() - 1) {
        throw new HumanReadableException(
            "Invalid config override \"%s=%s\" Expected <section>.<field>=<value>.",
            configKey, entry.getValue());
      }
      String value = entry.getValue();
      // If the value is empty, un-set the config
      if (value == null) {
        value = "";
      }

      // Overrides for locations of transitive children of cells are weird as the order of overrides
      // can affect the result (for example `-c a/b/c.k=v -c a/b//repositories.c=foo` causes an
      // interesting problem as the a/b/c cell gets created as a side-effect of the first override,
      // but the second override wants to change its identity).
      // It's generally a better idea to use the .buckconfig.local mechanism when overriding
      // repositories anyway, so here we simply disallow them.
      String section = configKey.substring(0, separatorIndex);
      if (section.equals("repositories")) {
        throw new HumanReadableException(
            "Overriding repository locations from the command line "
                + "is not supported. Please place a .buckconfig.local in the appropriate location and "
                + "use that instead.");
      }
      String field = configKey.substring(separatorIndex + 1);
      builder.put(cellName, section, field, value);
    }
    if (numThreads != null) {
      builder.put(
          RelativeCellName.ALL_CELLS_SPECIAL_NAME, "build", "threads", String.valueOf(numThreads));
    }
    if (noCache) {
      builder.put(RelativeCellName.ALL_CELLS_SPECIAL_NAME, "cache", "mode", "");
    }

    return builder.build();
  }

  @Override
  public LogConfigSetup getLogConfig() {
    return LogConfigSetup.DEFAULT_SETUP;
  }

  @Option(
    name = NO_CACHE_LONG_ARG,
    usage = "Whether to ignore the [cache] declared in .buckconfig."
  )
  private boolean noCache = false;

  @Nullable
  @Option(
    name = OUTPUT_TEST_EVENTS_TO_FILE_LONG_ARG,
    aliases = {"--output-events-to-file"},
    usage =
        "Serialize test-related event-bus events to the given file "
            + "as line-oriented JSON objects."
  )
  private String eventsOutputPath = null;

  @Option(
    name = PROFILE_PARSER_LONG_ARG,
    usage =
        "Enable profiling of buck.py internals (not the target being compiled) in the debug"
            + "log and trace."
  )
  private boolean enableParserProfiling = false;

  @Option(name = HELP_LONG_ARG, usage = "Prints the available options and exits.")
  private boolean help = false;

  /** @return {code true} if the {@code [cache]} in {@code .buckconfig} should be ignored. */
  public boolean isNoCache() {
    return noCache;
  }

  public Optional<Path> getEventsOutputPath() {
    if (eventsOutputPath == null) {
      return Optional.empty();
    } else {
      return Optional.of(Paths.get(eventsOutputPath));
    }
  }

  @Override
  public void printUsage(PrintStream stream) {
    CommandHelper.printShortDescription(this, stream);
    stream.println("Options:");
    new AdditionalOptionsCmdLineParser(this).printUsage(stream);
    stream.println();
  }

  @Override
  public Optional<ExitCode> runHelp(PrintStream stream) {
    if (help) {
      printUsage(stream);
      return Optional.of(ExitCode.SUCCESS);
    }
    return Optional.empty();
  }

  @Override
  public final ExitCode run(CommandRunnerParams params) throws IOException, InterruptedException {
    if (help) {
      printUsage(params.getConsole().getStdErr());
      return ExitCode.SUCCESS;
    }
    if (params.getConsole().getAnsi().isAnsiTerminal()) {
      ImmutableList<String> motd = params.getBuckConfig().getMessageOfTheDay();
      if (!motd.isEmpty()) {
        for (String line : motd) {
          params.getBuckEventBus().post(ConsoleEvent.info(line));
        }
      }
    }
    try (Closeable closeable = prepareExecutionContext(params)) {
      return runWithoutHelp(params);
    }
  }

  protected Closeable prepareExecutionContext(CommandRunnerParams params) {
    executionContext = createExecutionContext(params);
    return () -> {
      ExecutionContext context = executionContext;
      executionContext = null;
      context.close();
    };
  }

  public abstract ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException;

  protected CommandLineBuildTargetNormalizer getCommandLineBuildTargetNormalizer(
      BuckConfig buckConfig) {
    return new CommandLineBuildTargetNormalizer(buckConfig);
  }

  public boolean getEnableParserProfiling() {
    return enableParserProfiling;
  }

  public ImmutableList<TargetNodeSpec> parseArgumentsAsTargetNodeSpecs(
      BuckConfig config, Iterable<String> targetsAsArgs) {
    ImmutableList.Builder<TargetNodeSpec> specs = ImmutableList.builder();
    CommandLineTargetNodeSpecParser parser =
        new CommandLineTargetNodeSpecParser(config, new BuildTargetPatternTargetNodeParser());
    for (String arg : targetsAsArgs) {
      specs.addAll(parser.parse(config.getCellPathResolver(), arg));
    }
    return specs.build();
  }

  /**
   * @param cellNames
   * @param buildTargetNames The build targets to parse, represented as strings.
   * @return A set of {@link BuildTarget}s for the input buildTargetNames.
   */
  protected ImmutableSet<BuildTarget> getBuildTargets(
      CellPathResolver cellNames, Iterable<String> buildTargetNames) {
    ImmutableSet.Builder<BuildTarget> buildTargets = ImmutableSet.builder();

    // Parse all of the build targets specified by the user.
    for (String buildTargetName : buildTargetNames) {
      buildTargets.add(
          BuildTargetParser.INSTANCE.parse(
              buildTargetName, BuildTargetPatternParser.fullyQualified(), cellNames));
    }

    return buildTargets.build();
  }

  protected ExecutionContext getExecutionContext() {
    return executionContext;
  }

  private ExecutionContext createExecutionContext(CommandRunnerParams params) {
    return getExecutionContextBuilder(params).build();
  }

  protected ExecutionContext.Builder getExecutionContextBuilder(CommandRunnerParams params) {
    return ExecutionContext.builder()
        .setConsole(params.getConsole())
        .setBuckEventBus(params.getBuckEventBus())
        .setPlatform(params.getPlatform())
        .setEnvironment(params.getEnvironment())
        .setJavaPackageFinder(params.getJavaPackageFinder())
        .setExecutors(params.getExecutors())
        .setCellPathResolver(params.getCell().getCellPathResolver())
        .setBuildCellRootPath(params.getCell().getRoot())
        .setProcessExecutor(new DefaultProcessExecutor(params.getConsole()))
        .setDefaultTestTimeoutMillis(params.getBuckConfig().getDefaultTestTimeoutMillis())
        .setInclNoLocationClassesEnabled(
            params.getBuckConfig().getBooleanValue("test", "incl_no_location_classes", false))
        .setRuleKeyDiagnosticsMode(params.getBuckConfig().getRuleKeyDiagnosticsMode())
        .setConcurrencyLimit(getConcurrencyLimit(params.getBuckConfig()))
        .setPersistentWorkerPools(params.getPersistentWorkerPools())
        .setProjectFilesystemFactory(params.getProjectFilesystemFactory());
  }

  public ConcurrencyLimit getConcurrencyLimit(BuckConfig buckConfig) {
    return buckConfig.getView(ResourcesConfig.class).getConcurrencyLimit();
  }

  @Override
  public boolean isSourceControlStatsGatheringEnabled() {
    return false;
  }

  TargetGraphAndBuildTargets toVersionedTargetGraph(
      CommandRunnerParams params, TargetGraphAndBuildTargets targetGraphAndBuildTargets)
      throws VersionException, InterruptedException {
    return params
        .getVersionedTargetGraphCache()
        .toVersionedTargetGraph(
            params.getBuckEventBus(),
            params.getBuckConfig(),
            params.getTypeCoercerFactory(),
            targetGraphAndBuildTargets);
  }

  @Override
  public Iterable<BuckEventListener> getEventListeners(
      Map<ExecutorPool, ListeningExecutorService> executorPool,
      ScheduledExecutorService scheduledExecutorService) {
    return ImmutableList.of();
  }

  RuleKeyCacheScope<RuleKey> getDefaultRuleKeyCacheScope(
      CommandRunnerParams params, RuleKeyCacheRecycler.SettingsAffectingCache settings) {
    return params
        .getDefaultRuleKeyFactoryCacheRecycler()
        // First try to get the cache from the recycler.
        .map(recycler -> recycler.withRecycledCache(params.getBuckEventBus(), settings))
        // Otherwise, create a new one.
        .orElseGet(
            () ->
                new EventPostingRuleKeyCacheScope<>(
                    params.getBuckEventBus(),
                    new TrackedRuleKeyCache<>(
                        new DefaultRuleKeyCache<>(), new InstrumentingCacheStatsTracker())));
  }

  /**
   * @param buckConfig the configuration for resources
   * @return a memoized supplier for a ForkJoinPool that will be closed properly if initialized
   */
  protected CloseableMemoizedSupplier<ForkJoinPool> getForkJoinPoolSupplier(BuckConfig buckConfig) {
    ResourcesConfig resource = buckConfig.getView(ResourcesConfig.class);
    return CloseableMemoizedSupplier.of(
        () ->
            MostExecutors.forkJoinPoolWithThreadLimit(
                resource.getMaximumResourceAmounts().getCpu(), 16),
        ForkJoinPool::shutdownNow);
  }
}
