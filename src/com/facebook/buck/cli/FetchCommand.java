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
import com.facebook.buck.core.build.distributed.synchronization.impl.NoOpRemoteBuildRuleCompletionWaiter;
import com.facebook.buck.core.build.engine.config.CachingBuildEngineBuckConfig;
import com.facebook.buck.core.build.engine.delegate.LocalCachingBuildEngineDelegate;
import com.facebook.buck.core.build.engine.impl.CachingBuildEngine;
import com.facebook.buck.core.build.engine.impl.MetadataChecker;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphCache;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphFactory;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphProvider;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.transformer.impl.FetchTargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.file.HttpArchiveDescription;
import com.facebook.buck.file.HttpFileDescription;
import com.facebook.buck.file.RemoteFileDescription;
import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.file.downloader.impl.StackedDownloader;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.rules.keys.RuleKeyCacheScope;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.versions.VersionException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Optional;

public class FetchCommand extends BuildCommand {

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {

    if (getArguments().isEmpty()) {
      throw new CommandLineException("must specify at least one build target");
    }

    BuildEvent.Started started = BuildEvent.started(getArguments());
    params.getBuckEventBus().post(started);

    FetchTargetNodeToBuildRuleTransformer ruleGenerator = createFetchTransformer(params);
    ExitCode exitCode;

    try (CommandThreadManager pool =
        new CommandThreadManager("Fetch", getConcurrencyLimit(params.getBuckConfig())); ) {
      ActionGraphAndBuilder actionGraphAndBuilder;
      ImmutableSet<BuildTarget> buildTargets;
      try {
        ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);
        TargetGraphAndBuildTargets result =
            params
                .getParser()
                .buildTargetGraphForTargetNodeSpecs(
                    params.getCell(),
                    getEnableParserProfiling(),
                    pool.getListeningExecutorService(),
                    parseArgumentsAsTargetNodeSpecs(
                        params.getCell().getCellPathResolver(),
                        params.getBuckConfig(),
                        getArguments()),
                    parserConfig.getDefaultFlavorsMode());
        if (params.getBuckConfig().getBuildVersions()) {
          result = toVersionedTargetGraph(params, result);
        }
        actionGraphAndBuilder =
            Preconditions.checkNotNull(
                new ActionGraphProvider(
                        params.getBuckEventBus(),
                        ActionGraphFactory.create(
                            params.getBuckEventBus(),
                            params.getCell().getCellProvider(),
                            params.getPoolSupplier(),
                            params.getBuckConfig()),
                        new ActionGraphCache(
                            params.getBuckConfig().getMaxActionGraphCacheEntries()),
                        params.getRuleKeyConfiguration(),
                        params.getBuckConfig())
                    .getFreshActionGraph(ruleGenerator, result.getTargetGraph()));
        buildTargets = ruleGenerator.getDownloadableTargets();
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
                      params.getBuckConfig().getKeySeed(),
                      actionGraphAndBuilder.getActionGraph()));
          CachingBuildEngine buildEngine =
              new CachingBuildEngine(
                  localCachingBuildEngineDelegate,
                  Optional.empty(),
                  pool.getWeightedListeningExecutorService(),
                  new DefaultStepRunner(),
                  getBuildEngineMode().orElse(cachingBuildEngineBuckConfig.getBuildEngineMode()),
                  cachingBuildEngineBuckConfig.getBuildMetadataStorage(),
                  cachingBuildEngineBuckConfig.getBuildDepFiles(),
                  cachingBuildEngineBuckConfig.getBuildMaxDepFileCacheEntries(),
                  cachingBuildEngineBuckConfig.getBuildArtifactCacheSizeLimit(),
                  actionGraphAndBuilder.getActionGraphBuilder(),
                  sourcePathRuleFinder,
                  DefaultSourcePathResolver.from(sourcePathRuleFinder),
                  params.getBuildInfoStoreManager(),
                  cachingBuildEngineBuckConfig.getResourceAwareSchedulingInfo(),
                  cachingBuildEngineBuckConfig.getConsoleLogBuildRuleFailuresInline(),
                  RuleKeyFactories.of(
                      params.getRuleKeyConfiguration(),
                      localCachingBuildEngineDelegate.getFileHashCache(),
                      actionGraphAndBuilder.getActionGraphBuilder(),
                      params.getBuckConfig().getBuildInputRuleKeyFileSizeLimit(),
                      ruleKeyCacheScope.getCache()),
                  new NoOpRemoteBuildRuleCompletionWaiter());
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

  @Override
  public boolean isReadOnly() {
    return false;
  }

  private FetchTargetNodeToBuildRuleTransformer createFetchTransformer(CommandRunnerParams params) {
    Downloader downloader =
        StackedDownloader.createFromConfig(
            params.getBuckConfig(), params.getCell().getToolchainProvider());
    ImmutableSet<DescriptionWithTargetGraph<?>> fetchingDescriptions =
        ImmutableSet.of(
            new RemoteFileDescription(downloader),
            new HttpFileDescription(downloader),
            new HttpArchiveDescription(downloader));

    return new FetchTargetNodeToBuildRuleTransformer(fetchingDescriptions);
  }

  @Override
  public String getShortDescription() {
    return "downloads remote resources to your local machine";
  }
}
