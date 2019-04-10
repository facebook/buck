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

import com.facebook.buck.cli.PerfManifestCommand.Context;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.engine.manifest.Manifest;
import com.facebook.buck.core.io.ArchiveMemberPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.SupportsDependencyFileRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.rules.keys.DefaultRuleKeyCache;
import com.facebook.buck.rules.keys.RuleKeyAndInputs;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.rules.keys.TrackedRuleKeyCache;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.NamedTemporaryFile;
import com.facebook.buck.util.cache.InstrumentingCacheStatsTracker;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

/**
 * Tests performance of creating and manipulating {@link Manifest} objects. These are used for all
 * depfile-supporting rules.
 */
public class PerfManifestCommand extends AbstractPerfCommand<Context> {
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
      name = "--unsafe-ask-for-rule-inputs",
      usage =
          "This will ask the rules for their inputs as though they were built. This is unsafe and might cause problems with Buck's internal state.")
  private boolean unsafeGetInputsAfterBuilding = false;

  @Argument private List<String> arguments = new ArrayList<>();

  public List<String> getArguments() {
    return arguments;
  }

  @Override
  Context prepareTest(CommandRunnerParams params) {
    try {
      // Create a TargetGraph that is composed of the transitive closure of all of the dependent
      // BuildRules for the specified BuildTargetPaths.
      ImmutableSet<BuildTarget> targets = convertArgumentsToBuildTargets(params, getArguments());

      if (targets.isEmpty()) {
        throw new CommandLineException("must specify at least one build target");
      }

      TargetGraph targetGraph = getTargetGraph(params, targets);

      // Get a fresh action graph since we might unsafely run init from disks...
      // Also, we don't measure speed of this part.
      ActionGraphBuilder graphBuilder =
          params.getActionGraphProvider().getFreshActionGraph(targetGraph).getActionGraphBuilder();

      StackedFileHashCache fileHashCache = createStackedFileHashCache(params);

      ImmutableList<BuildRule> rulesInGraph = getRulesInGraph(graphBuilder, targets);

      TrackedRuleKeyCache<RuleKey> ruleKeyCache =
          new TrackedRuleKeyCache<>(
              new DefaultRuleKeyCache<>(), new InstrumentingCacheStatsTracker());
      RuleKeyFactories factories =
          RuleKeyFactories.of(
              params.getRuleKeyConfiguration(),
              fileHashCache,
              graphBuilder,
              Long.MAX_VALUE,
              ruleKeyCache);

      if (unsafeInitFromDisk) {
        printWarning(params, "Unsafely initializing rules from disk.");
        initializeRulesFromDisk(graphBuilder, rulesInGraph);
      } else {
        printWarning(params, "manipulating manifests may fail without --unsafe-init-from-disk.");
      }

      ImmutableMap<BuildRule, ImmutableSet<SourcePath>> usedInputs;
      if (unsafeGetInputsAfterBuilding) {
        printWarning(params, "Unsafely asking for rule inputs.");
        usedInputs = getInputsAfterBuildingLocally(params, graphBuilder, rulesInGraph);
      } else {
        usedInputs = ImmutableMap.of();
        printWarning(
            params,
            "manipulating manifests may be innacurate without --unsafe-ask-for-rule-inputs");
      }

      ImmutableMap<SupportsDependencyFileRuleKey, RuleKeyAndInputs> manifestKeys =
          computeManifestKeys(rulesInGraph, factories);

      return new Context(manifestKeys, graphBuilder, usedInputs);
    } catch (Exception e) {
      throw new BuckUncheckedExecutionException(
          e, "When inspecting serialization state of the action graph.");
    }
  }

  private static ImmutableMap<SupportsDependencyFileRuleKey, RuleKeyAndInputs> computeManifestKeys(
      ImmutableList<BuildRule> rulesInGraph, RuleKeyFactories factories) {
    return rulesInGraph.stream()
        .filter(rule -> rule instanceof SupportsDependencyFileRuleKey)
        .map(SupportsDependencyFileRuleKey.class::cast)
        .filter(SupportsDependencyFileRuleKey::useDependencyFileRuleKeys)
        .collect(
            ImmutableMap.toImmutableMap(
                rule -> rule,
                rule -> {
                  try {
                    return factories.getDepFileRuleKeyFactory().buildManifestKey(rule);
                  } catch (IOException e) {
                    throw new BuckUncheckedExecutionException(
                        e, "When computing manifest key for %s.", rule.getBuildTarget());
                  }
                }));
  }

  private static ImmutableMap<BuildRule, ImmutableSet<SourcePath>> getInputsAfterBuildingLocally(
      CommandRunnerParams params,
      ActionGraphBuilder graphBuilder,
      ImmutableList<BuildRule> rulesInGraph) {
    ImmutableMap.Builder<BuildRule, ImmutableSet<SourcePath>> usedInputs = ImmutableMap.builder();

    rulesInGraph.forEach(
        rule -> {
          if (rule instanceof SupportsDependencyFileRuleKey
              && ((SupportsDependencyFileRuleKey) rule).useDependencyFileRuleKeys()) {
            try {
              SourcePathResolver pathResolver =
                  DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
              usedInputs.put(
                  rule,
                  ImmutableSet.copyOf(
                      ((SupportsDependencyFileRuleKey) rule)
                          .getInputsAfterBuildingLocally(
                              BuildContext.builder()
                                  .setShouldDeleteTemporaries(false)
                                  .setBuildCellRootPath(params.getCell().getRoot())
                                  .setEventBus(params.getBuckEventBus())
                                  .setJavaPackageFinder(params.getJavaPackageFinder())
                                  .setSourcePathResolver(pathResolver)
                                  .build(),
                              params.getCell().getCellPathResolver())));
            } catch (Exception e) {
              throw new BuckUncheckedExecutionException(
                  e, "When asking %s for its inputs.", rule.getBuildTarget());
            }
          }
        });
    return usedInputs.build();
  }

  /** Our test context. */
  public static class Context {
    private final ImmutableMap<SupportsDependencyFileRuleKey, RuleKeyAndInputs> manifestKeys;
    private final BuildRuleResolver graphBuilder;
    private final ImmutableMap<BuildRule, ImmutableSet<SourcePath>> usedInputs;

    public Context(
        ImmutableMap<SupportsDependencyFileRuleKey, RuleKeyAndInputs> manifestKeys,
        ActionGraphBuilder graphBuilder,
        ImmutableMap<BuildRule, ImmutableSet<SourcePath>> usedInputs) {
      this.manifestKeys = manifestKeys;
      this.graphBuilder = graphBuilder;
      this.usedInputs = usedInputs;
    }
  }

  @Override
  protected String getComputationName() {
    return "manifest parse and manipulate";
  }

  @Override
  void runPerfTest(CommandRunnerParams params, Context context) throws Exception {
    for (Entry<SupportsDependencyFileRuleKey, RuleKeyAndInputs> entry :
        context.manifestKeys.entrySet()) {
      Manifest manifest = new Manifest(entry.getValue().getRuleKey());
      // Why do we add two entries? A lot of Manifest's complexity comes from de-duplicating entries
      // and data between multiple manifests. If we only add a single entry, it may not do that
      // work.
      Random random = new Random();
      manifest.addEntry(
          getFileHashLoader(random.nextInt()),
          entry.getValue().getRuleKey(),
          DefaultSourcePathResolver.from(new SourcePathRuleFinder(context.graphBuilder)),
          entry.getValue().getInputs(),
          context.usedInputs.getOrDefault(entry.getKey(), ImmutableSet.of()));
      manifest.addEntry(
          getFileHashLoader(random.nextInt()),
          entry.getValue().getRuleKey(),
          DefaultSourcePathResolver.from(new SourcePathRuleFinder(context.graphBuilder)),
          entry.getValue().getInputs(),
          context.usedInputs.getOrDefault(entry.getKey(), ImmutableSet.of()));

      // Includes serializing + deserializing. This should be super-fast relative to the above, but
      // that's okay.
      try (NamedTemporaryFile temporaryFile = new NamedTemporaryFile("dont", "care")) {
        try (OutputStream output =
            entry.getKey().getProjectFilesystem().newFileOutputStream(temporaryFile.get())) {
          manifest.serialize(output);
        }
        try (InputStream input =
            entry.getKey().getProjectFilesystem().newFileInputStream(temporaryFile.get())) {
          new Manifest(input);
        }
      }
    }
  }

  private FileHashLoader getFileHashLoader(int seed) {
    HashFunction hashFunction = Hashing.sha1();
    // We put Path hashcodes because we know that Path.toString() can be slow due to BuckUnixPath.
    return new FileHashLoader() {
      int getSeedFor(int hashCode) {
        // This just sort of gives us a stable value of 0 or 1 for a given hashCode w/ about 95% as
        // 0. That then means that between the two entries that we add, like 90% of paths will have
        // the same hash and 10% are different. Whether or not that reflects reality? ...
        return ((hashCode ^ seed) & 1024) > 960 ? 0 : 1;
      }

      @Override
      public HashCode get(Path path) {
        return hashFunction.newHasher().putInt(getSeedFor(path.hashCode())).hash();
      }

      @Override
      public long getSize(Path path) {
        return 0;
      }

      @Override
      public HashCode get(ArchiveMemberPath archiveMemberPath) {
        return hashFunction.newHasher().putInt(getSeedFor(archiveMemberPath.hashCode())).hash();
      }
    };
  }

  @Override
  public String getShortDescription() {
    return "provides facilities to audit build targets' classpaths";
  }
}
