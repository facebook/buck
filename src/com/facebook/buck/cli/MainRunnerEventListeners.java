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

package com.facebook.buck.cli;

import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;

import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.counters.CounterRegistry;
import com.facebook.buck.doctor.DefaultDefectReporter;
import com.facebook.buck.doctor.config.DoctorConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.chrome_trace.ChromeTraceBuckConfig;
import com.facebook.buck.event.listener.CacheRateStatsListener;
import com.facebook.buck.event.listener.ChromeTraceBuildListener;
import com.facebook.buck.event.listener.CriticalPathEventListener;
import com.facebook.buck.event.listener.JavaUtilsLoggingBuildListener;
import com.facebook.buck.event.listener.LoadBalancerEventsListener;
import com.facebook.buck.event.listener.LogUploaderListener;
import com.facebook.buck.event.listener.LoggingBuildListener;
import com.facebook.buck.event.listener.MachineReadableLoggerListener;
import com.facebook.buck.event.listener.ParserProfilerLoggerListener;
import com.facebook.buck.event.listener.RuleKeyCheckListener;
import com.facebook.buck.event.listener.RuleKeyCheckListenerConfig;
import com.facebook.buck.event.listener.RuleKeyDiagnosticsListener;
import com.facebook.buck.event.listener.RuleKeyLoggerListener;
import com.facebook.buck.event.listener.TopSlowTargetsEventListener;
import com.facebook.buck.event.utils.EventBusUtils;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.watchman.WatchmanDiagnosticEventListener;
import com.facebook.buck.log.GlobalStateManager;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.logd.client.LogStreamFactory;
import com.facebook.buck.remoteexecution.event.RemoteExecutionStatsProvider;
import com.facebook.buck.support.bgtasks.TaskManagerCommandScope;
import com.facebook.buck.support.build.report.BuildReportConfig;
import com.facebook.buck.support.build.report.BuildReportFileUploader;
import com.facebook.buck.support.build.report.BuildReportUtils;
import com.facebook.buck.support.build.report.RuleKeyLogFileUploader;
import com.facebook.buck.support.log.LogBuckConfig;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.concurrent.CommandThreadFactory;
import com.facebook.buck.util.concurrent.CommonThreadFactoryState;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.trace.uploader.types.TraceKind;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** Utilities for {@link MainRunner}. */
public class MainRunnerEventListeners {
  private static final Logger LOG = Logger.get(MainRunnerEventListeners.class);

  @SuppressWarnings("PMD.PrematureDeclaration")
  static ImmutableList<BuckEventListener> addEventListeners(
      BuildId buildId,
      BuckEventBus buckEventBus,
      ProjectFilesystem projectFilesystem,
      InvocationInfo invocationInfo,
      BuckConfig buckConfig,
      Optional<WebServer> webServer,
      Clock clock,
      ExecutionEnvironment executionEnvironment,
      CounterRegistry counterRegistry,
      Iterable<BuckEventListener> commandSpecificEventListeners,
      Optional<RemoteExecutionStatsProvider> reStatsProvider,
      TaskManagerCommandScope managerScope,
      LogStreamFactory logStreamFactory)
      throws IOException {
    ImmutableList.Builder<BuckEventListener> eventListenersBuilder =
        ImmutableList.<BuckEventListener>builder().add(new LoggingBuildListener());
    RuleKeyCheckListenerConfig ruleKeyCheckListenerConfig =
        buckConfig.getView(RuleKeyCheckListenerConfig.class);
    if (ruleKeyCheckListenerConfig.getEndpoint().isPresent()) {
      buckEventBus.register(
          new RuleKeyCheckListener(
              ruleKeyCheckListenerConfig, buckEventBus, executionEnvironment.getUsername()));
    }
    LogBuckConfig logBuckConfig = buckConfig.getView(LogBuckConfig.class);
    if (logBuckConfig.isJavaUtilsLoggingEnabled()) {
      eventListenersBuilder.add(new JavaUtilsLoggingBuildListener(projectFilesystem));
    }
    Path logDirectoryPath = invocationInfo.getLogDirectoryPath();
    Path criticalPathDir = projectFilesystem.resolve(logDirectoryPath);
    Path criticalPathLog = criticalPathDir.resolve(BuckConstant.BUCK_CRITICAL_PATH_LOG_FILE_NAME);
    projectFilesystem.mkdirs(criticalPathDir);
    CriticalPathEventListener criticalPathEventListener =
        new CriticalPathEventListener(logStreamFactory, criticalPathLog);
    buckEventBus.register(criticalPathEventListener);
    TopSlowTargetsEventListener slowTargetsEventListener = new TopSlowTargetsEventListener();
    buckEventBus.register(slowTargetsEventListener);

    ChromeTraceBuckConfig chromeTraceBuckConfig = buckConfig.getView(ChromeTraceBuckConfig.class);
    if (chromeTraceBuckConfig.isChromeTraceCreationEnabled()) {
      try {
        ChromeTraceBuildListener chromeTraceBuildListener =
            new ChromeTraceBuildListener(
                projectFilesystem,
                invocationInfo,
                clock,
                chromeTraceBuckConfig,
                managerScope,
                reStatsProvider,
                criticalPathEventListener,
                logStreamFactory);
        eventListenersBuilder.add(chromeTraceBuildListener);
      } catch (IOException e) {
        LOG.error("Unable to create ChromeTrace listener!");
      }
    } else {
      LOG.info("::: ChromeTrace listener disabled");
    }
    webServer.map(WebServer::createListener).ifPresent(eventListenersBuilder::add);

    ArtifactCacheBuckConfig artifactCacheConfig = new ArtifactCacheBuckConfig(buckConfig);


    CommonThreadFactoryState commonThreadFactoryState =
        GlobalStateManager.singleton().getThreadToCommandRegister();

    eventListenersBuilder.add(
        new LogUploaderListener(
            chromeTraceBuckConfig,
            invocationInfo.getLogFilePath(),
            invocationInfo.getLogDirectoryPath(),
            invocationInfo.getBuildId(),
            managerScope,
            TraceKind.BUILD_LOG));
    eventListenersBuilder.add(
        new LogUploaderListener(
            chromeTraceBuckConfig,
            invocationInfo.getSimpleConsoleOutputFilePath(),
            invocationInfo.getLogDirectoryPath(),
            invocationInfo.getBuildId(),
            managerScope,
            TraceKind.SIMPLE_CONSOLE_OUTPUT));
    eventListenersBuilder.add(
        new LogUploaderListener(
            chromeTraceBuckConfig,
            criticalPathLog,
            invocationInfo.getLogDirectoryPath(),
            invocationInfo.getBuildId(),
            managerScope,
            TraceKind.CRITICAL_PATH_LOG));

    if (logBuckConfig.isRuleKeyLoggerEnabled()) {

      Optional<RuleKeyLogFileUploader> keyLogFileUploader =
          createRuleKeyLogFileUploader(
              buckConfig, projectFilesystem, buckEventBus, clock, executionEnvironment, buildId);

      eventListenersBuilder.add(
          new RuleKeyLoggerListener(
              projectFilesystem,
              invocationInfo,
              MostExecutors.newSingleThreadExecutor(
                  new CommandThreadFactory(MainRunner.class.getName(), commonThreadFactoryState)),
              managerScope,
              keyLogFileUploader,
              logStreamFactory));
    }

    Optional<BuildReportFileUploader> buildReportFileUploader =
        createBuildReportFileUploader(buckConfig, buildId);

    eventListenersBuilder.add(
        new RuleKeyDiagnosticsListener(
            projectFilesystem,
            invocationInfo,
            MostExecutors.newSingleThreadExecutor(
                new CommandThreadFactory(MainRunner.class.getName(), commonThreadFactoryState)),
            managerScope,
            buildReportFileUploader));

    if (logBuckConfig.isMachineReadableLoggerEnabled()) {
      try {
        eventListenersBuilder.add(
            new MachineReadableLoggerListener(
                invocationInfo,
                projectFilesystem,
                MostExecutors.newSingleThreadExecutor(
                    new CommandThreadFactory(MainRunner.class.getName(), commonThreadFactoryState)),
                artifactCacheConfig.getArtifactCacheModes(),
                chromeTraceBuckConfig,
                invocationInfo.getLogDirectoryPath(),
                invocationInfo.getBuildId(),
                managerScope,
                logStreamFactory,
                logBuckConfig.isMachineReadableLoggerSyncOnClose()));
      } catch (FileNotFoundException e) {
        LOG.warn("Unable to open stream for machine readable log file.");
      }
    }

    eventListenersBuilder.add(new ParserProfilerLoggerListener(invocationInfo, projectFilesystem));
    eventListenersBuilder.add(new LoadBalancerEventsListener(counterRegistry));
    eventListenersBuilder.add(new CacheRateStatsListener(buckEventBus));
    eventListenersBuilder.add(new WatchmanDiagnosticEventListener(buckEventBus));
    eventListenersBuilder.addAll(commandSpecificEventListeners);

    ImmutableList<BuckEventListener> eventListeners = eventListenersBuilder.build();
    EventBusUtils.registerListeners(eventListeners, buckEventBus);
    return eventListeners;
  }

  static BuildEnvironmentDescription getBuildEnvironmentDescription(
      ExecutionEnvironment executionEnvironment,
      BuckConfig buckConfig) {
    ImmutableMap.Builder<String, String> environmentExtraData = ImmutableMap.builder();

    return BuildEnvironmentDescription.of(
        executionEnvironment,
        new ArtifactCacheBuckConfig(buckConfig).getArtifactCacheModesRaw(),
        environmentExtraData.build());
  }

  private static Optional<RuleKeyLogFileUploader> createRuleKeyLogFileUploader(
      BuckConfig buckConfig,
      ProjectFilesystem projectFilesystem,
      BuckEventBus buckEventBus,
      Clock clock,
      ExecutionEnvironment executionEnvironment,
      BuildId buildId) {
    if (BuildReportUtils.shouldUploadBuildReport(buckConfig)) {
      BuildReportConfig buildReportConfig = buckConfig.getView(BuildReportConfig.class);
      return Optional.of(
          new RuleKeyLogFileUploader(
              new DefaultDefectReporter(
                  projectFilesystem, buckConfig.getView(DoctorConfig.class), buckEventBus, clock),
              getBuildEnvironmentDescription(
                  executionEnvironment,
                  buckConfig),
              buildReportConfig.getEndpointUrl().get(),
              buildReportConfig.getEndpointTimeoutMs(),
              buildId));
    }
    return Optional.empty();
  }

  private static Optional<BuildReportFileUploader> createBuildReportFileUploader(
      BuckConfig buckConfig, BuildId buildId) {
    if (BuildReportUtils.shouldUploadBuildReport(buckConfig)) {
      BuildReportConfig buildReportConfig = buckConfig.getView(BuildReportConfig.class);
      return Optional.of(
          new BuildReportFileUploader(
              buildReportConfig.getEndpointUrl().get(),
              buildReportConfig.getEndpointTimeoutMs(),
              buildId));
    }
    return Optional.empty();
  }
}
