/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.command;

import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.build.engine.config.CachingBuildEngineBuckConfig;
import com.facebook.buck.core.build.engine.delegate.CachingBuildEngineDelegate;
import com.facebook.buck.core.build.engine.impl.CachingBuildEngine;
import com.facebook.buck.core.build.engine.type.BuildType;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfigurationSerializer;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.remoteexecution.interfaces.MetadataProvider;
import com.facebook.buck.rules.keys.RuleKeyCacheScope;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.rules.modern.builders.ModernBuildRuleBuilderFactory;
import com.facebook.buck.rules.modern.config.ModernBuildRuleConfig;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.Optional;

/** Used to build a given set of targets. */
public class BuildExecutor {
  private final ActionGraphAndBuilder actionGraphAndBuilder;
  private final WeightedListeningExecutorService executorService;
  private final CachingBuildEngineDelegate cachingBuildEngineDelegate;
  private final BuildExecutorArgs args;
  private final RuleKeyCacheScope<RuleKey> ruleKeyCacheScope;
  private final Optional<BuildType> buildEngineMode;
  private final Optional<ThriftRuleKeyLogger> ruleKeyLogger;
  private final MetadataProvider metadataProvider;
  private final TargetConfigurationSerializer targetConfigurationSerializer;

  private final CachingBuildEngine cachingBuildEngine;
  private final Build build;

  private volatile boolean isShutdown = false;

  public BuildExecutor(
      BuildExecutorArgs args,
      ExecutionContext executionContext,
      ActionGraphAndBuilder actionGraphAndBuilder,
      CachingBuildEngineDelegate cachingBuildEngineDelegate,
      WeightedListeningExecutorService executorService,
      boolean keepGoing,
      RuleKeyCacheScope<RuleKey> ruleKeyRuleKeyCacheScope,
      Optional<BuildType> buildEngineMode,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger,
      MetadataProvider metadataProvider,
      TargetConfigurationSerializer targetConfigurationSerializer,
      boolean remoteExecutionAutoEnabled,
      boolean forceDisableRemoteExecution) {
    this.actionGraphAndBuilder = actionGraphAndBuilder;
    this.executorService = executorService;
    this.args = args;
    this.cachingBuildEngineDelegate = cachingBuildEngineDelegate;
    this.buildEngineMode = buildEngineMode;
    this.ruleKeyLogger = ruleKeyLogger;
    this.ruleKeyCacheScope = ruleKeyRuleKeyCacheScope;
    this.metadataProvider = metadataProvider;
    this.targetConfigurationSerializer = targetConfigurationSerializer;

    // Init resources.
    this.cachingBuildEngine =
        createCachingBuildEngine(remoteExecutionAutoEnabled, forceDisableRemoteExecution);
    this.build =
        new Build(
            actionGraphAndBuilder.getActionGraphBuilder(),
            args.getCells().getRootCell(),
            cachingBuildEngine,
            args.getArtifactCacheFactory().newInstance(),
            args.getBuckConfig().getView(JavaBuckConfig.class).createDefaultJavaPackageFinder(),
            args.getClock(),
            executionContext,
            keepGoing);
  }

  /**
   * Builds the given targets synchronously. Failures are printed to the EventBus.
   *
   * @param targetsToBuild
   * @return exit code.
   */
  public ExitCode buildTargets(
      Iterable<BuildTarget> targetsToBuild, Optional<Path> pathToBuildReport) throws Exception {
    Preconditions.checkArgument(!isShutdown);
    try {
      return build.executeAndPrintFailuresToEventBus(
          targetsToBuild, args.getBuckEventBus(), args.getConsole(), pathToBuildReport);
    } catch (BuildTargetParseException e) {
      throw new HumanReadableException(
          e.getMessage()
              + "\n"
              + "Please check whether one of the targets passed as parameter has an empty or invalid name.");
    }
  }

  public CachingBuildEngine getCachingBuildEngine() {
    return cachingBuildEngine;
  }

  /**
   * Destroy any resources associated with this builder. Call this once only, when all
   * buildLocallyAndReturnExitCode calls have finished.
   */
  public synchronized void shutdown() {
    if (isShutdown) {
      return;
    }

    isShutdown = true;

    // Destroy resources.
    build.close();
    cachingBuildEngine.close();
  }

  private CachingBuildEngine createCachingBuildEngine(
      boolean remoteExecutionAutoEnabled, boolean forceDisableRemoteExecution) {
    CachingBuildEngineBuckConfig engineConfig =
        args.getBuckConfig().getView(CachingBuildEngineBuckConfig.class);

    return new CachingBuildEngine(
        cachingBuildEngineDelegate,
        ModernBuildRuleBuilderFactory.getBuildStrategy(
            args.getBuckConfig().getView(ModernBuildRuleConfig.class),
            args.getBuckConfig().getView(RemoteExecutionConfig.class),
            actionGraphAndBuilder.getActionGraphBuilder(),
            args.getCells().getRootCell(),
            args.getCells().getRootCell().getCellPathResolver(),
            cachingBuildEngineDelegate.getFileHashCache(),
            args.getBuckEventBus(),
            metadataProvider,
            remoteExecutionAutoEnabled,
            forceDisableRemoteExecution),
        executorService,
        buildEngineMode.orElse(engineConfig.getBuildEngineMode()),
        engineConfig.getBuildDepFiles(),
        engineConfig.getBuildMaxDepFileCacheEntries(),
        engineConfig.getBuildArtifactCacheSizeLimit(),
        actionGraphAndBuilder.getActionGraphBuilder(),
        actionGraphAndBuilder.getBuildEngineActionToBuildRuleResolver(),
        targetConfigurationSerializer,
        args.getBuildInfoStoreManager(),
        engineConfig.getResourceAwareSchedulingInfo(),
        engineConfig.getConsoleLogBuildRuleFailuresInline(),
        RuleKeyFactories.of(
            args.getRuleKeyConfiguration(),
            cachingBuildEngineDelegate.getFileHashCache(),
            actionGraphAndBuilder.getActionGraphBuilder(),
            args.getBuckConfig().getView(BuildBuckConfig.class).getBuildInputRuleKeyFileSizeLimit(),
            ruleKeyCacheScope.getCache(),
            ruleKeyLogger));
  }

  public Build getBuild() {
    return build;
  }
}
