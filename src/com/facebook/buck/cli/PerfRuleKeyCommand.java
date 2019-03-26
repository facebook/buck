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

import com.facebook.buck.cli.PerfRuleKeyCommand.PreparedState;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.build.engine.buildinfo.BuildInfo;
import com.facebook.buck.core.build.engine.buildinfo.DefaultOnDiskBuildInfo;
import com.facebook.buck.core.build.engine.cache.manager.BuildInfoStoreManager;
import com.facebook.buck.core.build.engine.config.CachingBuildEngineBuckConfig;
import com.facebook.buck.core.build.engine.impl.DefaultRuleDepsCache;
import com.facebook.buck.core.build.engine.type.MetadataStorage;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rulekey.calculator.ParallelRuleKeyCalculator;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.attr.InitializableFromDisk;
import com.facebook.buck.core.rules.attr.SupportsDependencyFileRuleKey;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.util.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.io.filesystem.EmbeddedCellBuckOutInfo;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemDelegate;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.rules.keys.DefaultRuleKeyCache;
import com.facebook.buck.rules.keys.DependencyFileEntry;
import com.facebook.buck.rules.keys.DependencyFileRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.rules.keys.RuleKeyFactory;
import com.facebook.buck.rules.keys.TrackedRuleKeyCache;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.InstrumentingCacheStatsTracker;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

/** Tests performance of computing the various rulekeys. */
public class PerfRuleKeyCommand extends AbstractPerfCommand<PreparedState> {

  @Option(
      name = "--key-type",
      usage =
          "which key type to compute. depfile/manifest may require a --deep build and the --unsafe-init-from-disk flag.")
  private KeyType keyType = KeyType.DEFAULT;

  // TODO(cjhopman): We should consider a mode that does a --deep build and then computes these
  // keys... but this is so much faster and simpler.
  // TODO(cjhopman): We could actually get a build that just traverses the graph and builds only the
  // nodes that support InitializableFromDisk.
  // TODO(cjhopman): Or just delete InitializatableFromDisk.
  @Option(
      name = "--unsafe-init-from-disk",
      usage =
          "Run all rules from-disk initialization. This is unsafe and might cause problems with Buck's internal state.")
  private boolean unsafeInitFromDisk = false;

  // TODO(cjhopman): We should consider a mode that does a --deep build and then computes these
  // keys... but this is so much faster and simpler.
  // TODO(cjhopman): We could actually get a build that just traverses the graph and builds only the
  // nodes that support depfiles.
  @Option(
      name = "--unsafe-read-on-disk-depfiles",
      usage =
          "Read depfiles that are on disk and use those as the used inputs for computing depfile keys. This is unsafe and might cause problems with Buck's internal state.")
  private boolean unsafeReadOnDiskDepfiles = false;

  @Argument private List<String> arguments = new ArrayList<>();

  private enum KeyType {
    DEFAULT,
    INPUT,
    DEPFILE,
    MANIFEST
  }

  @Override
  PreparedState prepareTest(CommandRunnerParams params) {
    try {
      // Create a TargetGraph that is composed of the transitive closure of all of the dependent
      // BuildRules for the specified BuildTargetPaths.
      ImmutableSet<BuildTarget> targets = convertArgumentsToBuildTargets(params, arguments);

      if (targets.isEmpty()) {
        throw new CommandLineException("must specify at least one build target");
      }

      TargetGraph targetGraph = getTargetGraph(params, targets);

      // Get a fresh action graph since we might unsafely run init from disks...
      // Also, we don't measure speed of this part.
      ActionGraphBuilder graphBuilder =
          params.getActionGraphProvider().getFreshActionGraph(targetGraph).getActionGraphBuilder();

      ImmutableList<BuildRule> rulesInGraph = getRulesInGraph(graphBuilder, targets);

      if (keyType == KeyType.DEPFILE || keyType == KeyType.MANIFEST) {
        if (unsafeInitFromDisk) {
          printWarning(params, "Unsafely initializing rules from disk.");
          initializeRulesFromDisk(graphBuilder, rulesInGraph);
        } else {
          printWarning(
              params, "Computing depfile/manifest keys may fail without --unsafe-init-from-disk.");
        }
      }

      Map<BuildRule, ImmutableList<DependencyFileEntry>> usedInputs = new HashMap<>();
      if (keyType == KeyType.DEPFILE) {
        if (unsafeReadOnDiskDepfiles) {
          printWarning(params, "Unsafely reading on-disk depfiles.");
          usedInputs = readDepFiles(params, rulesInGraph);
        } else {
          printWarning(
              params,
              "Computing depfile keys may be innacurate without --unsafe-read-on-disk-depfiles");
        }
      }

      ListeningExecutorService service =
          MoreExecutors.listeningDecorator(
              MostExecutors.newMultiThreadExecutor(
                  "rulekey-computation",
                  params.getBuckConfig().getView(BuildBuckConfig.class).getNumThreads()));

      return new PreparedState(service, graphBuilder, rulesInGraph, usedInputs);
    } catch (Exception e) {
      throw new BuckUncheckedExecutionException(
          e, "When inspecting serialization state of the action graph.");
    }
  }

  /** The state prepared for us to compute keys. */
  static class PreparedState {
    private final ListeningExecutorService service;
    private final BuildRuleResolver graphBuilder;
    private final ImmutableList<BuildRule> rulesInGraph;
    private final Map<BuildRule, ImmutableList<DependencyFileEntry>> usedInputs;

    PreparedState(
        ListeningExecutorService service,
        BuildRuleResolver graphBuilder,
        ImmutableList<BuildRule> rulesInGraph,
        Map<BuildRule, ImmutableList<DependencyFileEntry>> usedInputs) {
      this.service = service;
      this.graphBuilder = graphBuilder;
      this.rulesInGraph = rulesInGraph;
      this.usedInputs = usedInputs;
    }
  }

  @Override
  protected String getComputationName() {
    return String.format("%s key", keyType.toString().toLowerCase());
  }

  @Override
  void runPerfTest(CommandRunnerParams params, PreparedState state) throws Exception {
    RuleKeyFactory<?> keyFactory = getRuleKeyFactory(params, state);

    ParallelRuleKeyCalculator<?> keyCalculator =
        new ParallelRuleKeyCalculator<>(
            state.service,
            keyFactory,
            new DefaultRuleDepsCache(state.graphBuilder),
            (buckEventBus, buildRule) -> () -> {});

    List<ListenableFuture<?>> futures = new ArrayList<>();
    for (BuildRule buildRule : state.rulesInGraph) {
      futures.add(keyCalculator.calculate(params.getBuckEventBus(), buildRule));
    }
    Futures.allAsList(futures).get();
  }

  private RuleKeyFactory<?> getRuleKeyFactory(CommandRunnerParams params, PreparedState context)
      throws InterruptedException {
    // We recreate the filehashcache and key factory in each run to ensure they don't benefit from
    // internal caching.
    StackedFileHashCache fileHashCache = createStackedFileHashCache(params);

    TrackedRuleKeyCache<RuleKey> ruleKeyCache =
        new TrackedRuleKeyCache<>(
            new DefaultRuleKeyCache<>(), new InstrumentingCacheStatsTracker());
    RuleKeyFactories factories =
        RuleKeyFactories.of(
            params.getRuleKeyConfiguration(),
            fileHashCache,
            context.graphBuilder,
            Long.MAX_VALUE,
            ruleKeyCache);

    RuleKeyFactory<?> keyFactory;
    DependencyFileRuleKeyFactory depFileRuleKeyFactory = factories.getDepFileRuleKeyFactory();
    switch (keyType) {
      case DEFAULT:
        keyFactory = factories.getDefaultRuleKeyFactory();
        break;
      case INPUT:
        keyFactory =
            buildRule ->
                buildRule instanceof SupportsInputBasedRuleKey
                    ? factories.getInputBasedRuleKeyFactory().build(buildRule)
                    : null;
        break;
      case DEPFILE:
        keyFactory =
            buildRule -> {
              try {
                return buildRule instanceof SupportsDependencyFileRuleKey
                    ? depFileRuleKeyFactory.build(
                        (SupportsDependencyFileRuleKey) buildRule,
                        context.usedInputs.computeIfAbsent(
                            buildRule, ignored -> ImmutableList.of()))
                    : null;
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            };
        break;
      case MANIFEST:
        keyFactory =
            buildRule -> {
              try {
                return buildRule instanceof SupportsDependencyFileRuleKey
                    ? depFileRuleKeyFactory.buildManifestKey(
                        (SupportsDependencyFileRuleKey) buildRule)
                    : null;
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            };
        break;
      default:
        throw new RuntimeException();
    }
    return keyFactory;
  }

  private Map<BuildRule, ImmutableList<DependencyFileEntry>> readDepFiles(
      CommandRunnerParams params, ImmutableList<BuildRule> rulesInGraph) {
    Map<BuildRule, ImmutableList<DependencyFileEntry>> usedInputs = new ConcurrentHashMap<>();
    try (BuildInfoStoreManager buildInfoStoreManager = new BuildInfoStoreManager()) {
      MetadataStorage metadataStorage =
          params
              .getBuckConfig()
              .getView(CachingBuildEngineBuckConfig.class)
              .getBuildMetadataStorage();

      rulesInGraph.forEach(
          rule -> {
            if (rule instanceof SupportsDependencyFileRuleKey
                && ((SupportsDependencyFileRuleKey) rule).useDependencyFileRuleKeys()) {
              try {
                ImmutableList<String> depFile =
                    new DefaultOnDiskBuildInfo(
                            rule.getBuildTarget(),
                            rule.getProjectFilesystem(),
                            buildInfoStoreManager.get(rule.getProjectFilesystem(), metadataStorage))
                        .getValues(BuildInfo.MetadataKey.DEP_FILE)
                        .orElseThrow(
                            () ->
                                new RuntimeException(
                                    String.format(
                                        "Couldn't find depfile for %s.", rule.getBuildTarget())));

                usedInputs.put(
                    rule,
                    depFile
                        .stream()
                        .map(ObjectMappers.fromJsonFunction(DependencyFileEntry.class))
                        .collect(ImmutableList.toImmutableList()));
              } catch (Exception e) {
                throw new BuckUncheckedExecutionException(
                    e, "When reading on-disk depfile for %s.", rule.getBuildTarget());
              }
            }
          });
      return usedInputs;
    }
  }

  private void initializeRulesFromDisk(
      ActionGraphBuilder graphBuilder, ImmutableList<BuildRule> rulesInGraph) {
    rulesInGraph.forEach(
        rule -> {
          if (rule instanceof InitializableFromDisk) {
            try {
              ((InitializableFromDisk<?>) rule)
                  .initializeFromDisk(
                      DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder)));
            } catch (Exception e) {
              throw new BuckUncheckedExecutionException(
                  e, "When initializing %s.", rule.getBuildTarget());
            }
          }
        });
  }

  private ImmutableList<BuildRule> getRulesInGraph(
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
  private StackedFileHashCache createStackedFileHashCache(CommandRunnerParams params)
      throws InterruptedException {
    FileHashCacheMode cacheMode =
        params.getCell().getBuckConfig().getView(BuildBuckConfig.class).getFileHashCacheMode();

    return new StackedFileHashCache(
        ImmutableList.<ProjectFileHashCache>builder()
            .addAll(
                params
                    .getCell()
                    .getAllCells()
                    .stream()
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
        realFilesystem.getRootPath().getFileSystem(),
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
  public String getShortDescription() {
    return "provides facilities to audit build targets' classpaths";
  }
}
