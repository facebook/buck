/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.command.Build;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.build.distributed.synchronization.impl.NoOpRemoteBuildRuleCompletionWaiter;
import com.facebook.buck.core.build.engine.config.CachingBuildEngineBuckConfig;
import com.facebook.buck.core.build.engine.delegate.LocalCachingBuildEngineDelegate;
import com.facebook.buck.core.build.engine.impl.CachingBuildEngine;
import com.facebook.buck.core.build.engine.impl.MetadataChecker;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.cell.CellConfig;
import com.facebook.buck.core.cell.CellName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphCache;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphFactory;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphProvider;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.file.HttpArchive;
import com.facebook.buck.file.HttpFile;
import com.facebook.buck.file.RemoteFile;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.rules.keys.RuleKeyCacheScope;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.versions.VersionException;
import com.google.common.collect.ImmutableSet;
import java.util.Objects;
import java.util.Optional;

public class FetchCommand extends BuildCommand {

  @Override
  protected void addCommandSpecificConfigOverrides(CellConfig.Builder builder) {
    builder.put(CellName.ALL_CELLS_SPECIAL_NAME, "download", "in_build", "true");
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    if (getArguments().isEmpty()) {
      throw new CommandLineException("must specify at least one build target");
    }

    BuildEvent.Started started = BuildEvent.started(getArguments());
    params.getBuckEventBus().post(started);

    ExitCode exitCode;

    try (CommandThreadManager pool =
        new CommandThreadManager("Fetch", getConcurrencyLimit(params.getBuckConfig()))) {
      ActionGraphAndBuilder actionGraphAndBuilder;
      ImmutableSet<BuildTarget> buildTargets;
      try {
        ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);
        TargetGraphAndBuildTargets result =
            params
                .getParser()
                .buildTargetGraphWithoutConfigurationTargets(
                    createParsingContext(params.getCell(), pool.getListeningExecutorService())
                        .withApplyDefaultFlavorsMode(parserConfig.getDefaultFlavorsMode())
                        .withSpeculativeParsing(SpeculativeParsing.ENABLED),
                    parseArgumentsAsTargetNodeSpecs(
                        params.getCell(), params.getBuckConfig(), getArguments()),
                    params.getTargetConfiguration());
        if (params.getBuckConfig().getView(BuildBuckConfig.class).getBuildVersions()) {
          result = toVersionedTargetGraph(params, result);
        }
        actionGraphAndBuilder =
            Objects.requireNonNull(
                new ActionGraphProvider(
                        params.getBuckEventBus(),
                        ActionGraphFactory.create(
                            params.getBuckEventBus(),
                            params.getCell().getCellProvider(),
                            params.getExecutors(),
                            params.getBuckConfig()),
                        new ActionGraphCache(
                            params
                                .getBuckConfig()
                                .getView(BuildBuckConfig.class)
                                .getMaxActionGraphCacheEntries()),
                        params.getRuleKeyConfiguration(),
                        params.getBuckConfig())
                    .getFreshActionGraph(result.getTargetGraph()));
        buildTargets =
            RichStream.from(actionGraphAndBuilder.getActionGraph().getNodes())
                .filter(rule -> isDownloadableRule(rule))
                .map(BuildRule::getBuildTarget)
                .collect(ImmutableSet.toImmutableSet());
      } catch (BuildFileParseException | VersionException e) {
        params
            .getBuckEventBus()
            .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
        return ExitCode.PARSE_ERROR;
      }

      MetadataChecker.checkAndCleanIfNeeded(params.getCell());
      CachingBuildEngineBuckConfig cachingBuildEngineBuckConfig =
          params.getBuckConfig().getView(CachingBuildEngineBuckConfig.class);
      LocalCachingBuildEngineDelegate localCachingBuildEngineDelegate =
          new LocalCachingBuildEngineDelegate(params.getFileHashCache());
      SourcePathRuleFinder sourcePathRuleFinder =
          new SourcePathRuleFinder(actionGraphAndBuilder.getActionGraphBuilder());
      try (RuleKeyCacheScope<RuleKey> ruleKeyCacheScope =
              getDefaultRuleKeyCacheScope(
                  params,
                  new RuleKeyCacheRecycler.SettingsAffectingCache(
                      params.getBuckConfig().getView(BuildBuckConfig.class).getKeySeed(),
                      actionGraphAndBuilder.getActionGraph()));
          CachingBuildEngine buildEngine =
              new CachingBuildEngine(
                  localCachingBuildEngineDelegate,
                  Optional.empty(),
                  pool.getWeightedListeningExecutorService(),
                  getBuildEngineMode().orElse(cachingBuildEngineBuckConfig.getBuildEngineMode()),
                  cachingBuildEngineBuckConfig.getBuildMetadataStorage(),
                  cachingBuildEngineBuckConfig.getBuildDepFiles(),
                  cachingBuildEngineBuckConfig.getBuildMaxDepFileCacheEntries(),
                  cachingBuildEngineBuckConfig.getBuildArtifactCacheSizeLimit(),
                  actionGraphAndBuilder.getActionGraphBuilder(),
                  sourcePathRuleFinder,
                  DefaultSourcePathResolver.from(sourcePathRuleFinder),
                  params.getTargetConfigurationSerializer(),
                  params.getBuildInfoStoreManager(),
                  cachingBuildEngineBuckConfig.getResourceAwareSchedulingInfo(),
                  cachingBuildEngineBuckConfig.getConsoleLogBuildRuleFailuresInline(),
                  RuleKeyFactories.of(
                      params.getRuleKeyConfiguration(),
                      localCachingBuildEngineDelegate.getFileHashCache(),
                      actionGraphAndBuilder.getActionGraphBuilder(),
                      params
                          .getBuckConfig()
                          .getView(BuildBuckConfig.class)
                          .getBuildInputRuleKeyFileSizeLimit(),
                      ruleKeyCacheScope.getCache()),
                  new NoOpRemoteBuildRuleCompletionWaiter(),
                  cachingBuildEngineBuckConfig.getManifestServiceIfEnabled(
                      params.getManifestServiceSupplier()));
          Build build =
              new Build(
                  actionGraphAndBuilder.getActionGraphBuilder(),
                  params.getCell(),
                  buildEngine,
                  params.getArtifactCacheFactory().newInstance(),
                  params
                      .getBuckConfig()
                      .getView(JavaBuckConfig.class)
                      .createDefaultJavaPackageFinder(),
                  params.getClock(),
                  getExecutionContext(),
                  isKeepGoing())) {
        exitCode =
            build.executeAndPrintFailuresToEventBus(
                buildTargets,
                params.getBuckEventBus(),
                params.getConsole(),
                getPathToBuildReport(params.getBuckConfig()));
      }
    }

    params.getBuckEventBus().post(BuildEvent.finished(started, exitCode));

    return exitCode;
  }

  // TODO(cjhopman): This should be done via a Provider/Interface. In addition, that interface
  // should expose a function that takes a Downloader so we don't need the hacky config override.
  private boolean isDownloadableRule(BuildRule rule) {
    return rule instanceof HttpFile || rule instanceof RemoteFile || rule instanceof HttpArchive;
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public String getShortDescription() {
    return "downloads remote resources to your local machine";
  }
}
