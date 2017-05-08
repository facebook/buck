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

import com.facebook.buck.android.DefaultAndroidDirectoryResolver;
import com.facebook.buck.command.Build;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.file.Downloader;
import com.facebook.buck.file.RemoteFileDescription;
import com.facebook.buck.file.StackedDownloader;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.CachingBuildEngineBuckConfig;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.LocalCachingBuildEngineDelegate;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.rules.keys.RuleKeyCacheScope;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.versions.VersionException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class FetchCommand extends BuildCommand {

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {

    if (getArguments().isEmpty()) {
      params
          .getBuckEventBus()
          .post(ConsoleEvent.severe("Must specify at least one build target to fetch."));
      return 1;
    }

    // Post the build started event, setting it to the Parser recorded start time if appropriate.
    BuildEvent.Started started = BuildEvent.started(getArguments());
    if (params.getParser().getParseStartTime().isPresent()) {
      params.getBuckEventBus().post(started, params.getParser().getParseStartTime().get());
    } else {
      params.getBuckEventBus().post(started);
    }

    FetchTargetNodeToBuildRuleTransformer ruleGenerator = createFetchTransformer(params);
    int exitCode;
    try (CommandThreadManager pool =
        new CommandThreadManager("Fetch", getConcurrencyLimit(params.getBuckConfig()))) {
      ActionGraphAndResolver actionGraphAndResolver;
      ImmutableSet<BuildTarget> buildTargets;
      try {
        ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);
        TargetGraphAndBuildTargets result =
            params
                .getParser()
                .buildTargetGraphForTargetNodeSpecs(
                    params.getBuckEventBus(),
                    params.getCell(),
                    getEnableParserProfiling(),
                    pool.getExecutor(),
                    parseArgumentsAsTargetNodeSpecs(params.getBuckConfig(), getArguments()),
                    parserConfig.getDefaultFlavorsMode());
        if (params.getBuckConfig().getBuildVersions()) {
          result = toVersionedTargetGraph(params, result);
        }
        actionGraphAndResolver =
            Preconditions.checkNotNull(
                ActionGraphCache.getFreshActionGraph(
                    params.getBuckEventBus(), ruleGenerator, result.getTargetGraph()));
        buildTargets = ruleGenerator.getDownloadableTargets();
      } catch (BuildTargetException | BuildFileParseException | VersionException e) {
        params
            .getBuckEventBus()
            .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
        return 1;
      }

      MetadataChecker.checkAndCleanIfNeeded(params.getCell());
      CachingBuildEngineBuckConfig cachingBuildEngineBuckConfig =
          params.getBuckConfig().getView(CachingBuildEngineBuckConfig.class);
      LocalCachingBuildEngineDelegate localCachingBuildEngineDelegate =
          new LocalCachingBuildEngineDelegate(params.getFileHashCache());
      try (RuleKeyCacheScope<RuleKey> ruleKeyCacheScope =
              getDefaultRuleKeyCacheScope(
                  params,
                  new RuleKeyCacheRecycler.SettingsAffectingCache(
                      params.getBuckConfig().getKeySeed(),
                      actionGraphAndResolver.getActionGraph()));
          CachingBuildEngine buildEngine =
              new CachingBuildEngine(
                  localCachingBuildEngineDelegate,
                  pool.getExecutor(),
                  pool.getExecutor(),
                  new DefaultStepRunner(),
                  getBuildEngineMode().orElse(cachingBuildEngineBuckConfig.getBuildEngineMode()),
                  cachingBuildEngineBuckConfig.getBuildMetadataStorage(),
                  cachingBuildEngineBuckConfig.getBuildDepFiles(),
                  cachingBuildEngineBuckConfig.getBuildMaxDepFileCacheEntries(),
                  cachingBuildEngineBuckConfig.getBuildArtifactCacheSizeLimit(),
                  actionGraphAndResolver.getResolver(),
                  params.getBuildInfoStoreManager(),
                  cachingBuildEngineBuckConfig.getResourceAwareSchedulingInfo(),
                  RuleKeyFactories.of(
                      params.getBuckConfig().getKeySeed(),
                      localCachingBuildEngineDelegate.getFileHashCache(),
                      actionGraphAndResolver.getResolver(),
                      cachingBuildEngineBuckConfig.getBuildInputRuleKeyFileSizeLimit(),
                      ruleKeyCacheScope.getCache()));
          Build build =
              createBuild(
                  params.getBuckConfig(),
                  actionGraphAndResolver.getActionGraph(),
                  actionGraphAndResolver.getResolver(),
                  params.getCell(),
                  params.getAndroidPlatformTargetSupplier(),
                  buildEngine,
                  params.getArtifactCacheFactory().newInstance(),
                  params.getConsole(),
                  params.getBuckEventBus(),
                  Optional.empty(),
                  params.getPersistentWorkerPools(),
                  params.getPlatform(),
                  params.getEnvironment(),
                  params.getClock(),
                  Optional.empty(),
                  Optional.empty(),
                  params.getExecutors())) {
        exitCode =
            build.executeAndPrintFailuresToEventBus(
                buildTargets,
                isKeepGoing(),
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
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            params.getCell().getRoot().getFileSystem(),
            params.getEnvironment(),
            Optional.empty(),
            Optional.empty());

    Optional<Path> sdkDir = resolver.getSdkOrAbsent();

    Downloader downloader = StackedDownloader.createFromConfig(params.getBuckConfig(), sdkDir);
    Description<?> description = new RemoteFileDescription(downloader);
    return new FetchTargetNodeToBuildRuleTransformer(ImmutableSet.of(description));
  }

  @Override
  public String getShortDescription() {
    return "downloads remote resources to your local machine";
  }
}
