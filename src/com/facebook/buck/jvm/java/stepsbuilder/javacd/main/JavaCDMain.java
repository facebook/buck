/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.jvm.java.stepsbuilder.javacd.main;

import com.facebook.buck.cd.model.java.BuildJavaCommand;
import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.downwardapi.protocol.DownwardProtocolType;
import com.facebook.buck.downwardapi.utils.DownwardApiConstants;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.event.isolated.DefaultIsolatedEventBus;
import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import com.facebook.buck.io.namedpipes.NamedPipeWriter;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.ClassLoaderCache;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.util.JsonFormat;
import java.io.FileReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/**
 * JavaCD main class.
 *
 * <p>This provides a simple executable that can run any of the javacd actions.
 */
public class JavaCDMain {
  private static final ScheduledExecutorService MONITORING_THREAD_POOL =
      Executors.newSingleThreadScheduledExecutor();

  private static final NamedPipeFactory NAMED_PIPE_FACTORY = NamedPipeFactory.getFactory();
  private static final DownwardProtocolType DOWNWARD_PROTOCOL_TYPE = DownwardProtocolType.BINARY;
  private static final DownwardProtocol DOWNWARD_PROTOCOL =
      DOWNWARD_PROTOCOL_TYPE.getDownwardProtocol();

  @Option(name = "--action-id", required = true)
  private String actionId;

  @Option(name = "--command-file", required = true)
  private Path commandFile;

  /** Main entrypoint of JavaCD worker tool. */
  public static void main(String[] args) {
    JavaCDMain main = new JavaCDMain();
    CmdLineParser parser = new CmdLineParser(main);
    try {
      parser.parseArgument(args);
      main.run();
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      System.exit(1);
    }
    System.exit(0);
  }

  private static String getNonNullValue(ImmutableMap<String, String> envs, String key) {
    return Objects.requireNonNull(envs.get(key), () -> "Missing env var: " + key);
  }

  void run() {
    ActionId actionId = ActionId.of(this.actionId);

    Console console = createConsole();
    int exitCode = 0;

    try {
      Thread.setDefaultUncaughtExceptionHandler(
          (t, e) -> MainUtils.handleExceptionAndTerminate(t, console, e));

      MONITORING_THREAD_POOL.scheduleAtFixedRate(
          MainUtils::logCurrentJavacdState, 1, 10, TimeUnit.SECONDS);

      try (NamedPipeWriter eventNamedPipe =
              NAMED_PIPE_FACTORY.connectAsWriter(
                  Paths.get(
                      getNonNullValue(
                          EnvVariablesProvider.getSystemEnv(),
                          DownwardApiConstants.ENV_EVENT_PIPE)));
          OutputStream eventsOutputStream = eventNamedPipe.getOutputStream();
          ClassLoaderCache classLoaderCache = new ClassLoaderCache()) {
        BuildJavaCommand.Builder builder = BuildJavaCommand.newBuilder();
        try (Reader reader = new FileReader(commandFile.toFile())) {
          JsonFormat.parser().ignoringUnknownFields().merge(reader, builder);
        }
        BuildJavaCommand buildJavaCommand = builder.build();

        BuildId buildUuid = new BuildId();
        ProcessExecutor processExecutor = new DefaultProcessExecutor(console);
        Platform platform = Platform.detect();
        // no need to measure thread CPU time as this is an external process and we do not pass
        // thread time back to buck with Downward API
        Clock clock = new DefaultClock(false);

        try (IsolatedEventBus eventBus =
            new DefaultIsolatedEventBus(
                buildUuid, eventsOutputStream, clock, DOWNWARD_PROTOCOL, actionId)) {
          StepExecutionResult stepExecutionResult =
              BuildJavaCommandExecutor.executeBuildJavaCommand(
                  classLoaderCache,
                  actionId,
                  buildJavaCommand,
                  eventBus,
                  platform,
                  processExecutor,
                  console,
                  clock);
          if (!stepExecutionResult.isSuccess()) {
            System.err.println(stepExecutionResult.getStderr().orElse(""));
            stepExecutionResult.getCause().ifPresent(Throwable::printStackTrace);
          }
          exitCode = stepExecutionResult.getExitCode();
        }
      }
    } catch (Exception e) {
      MainUtils.handleExceptionAndTerminate(Thread.currentThread(), console, e);
    }
    System.exit(exitCode);
  }

  private static Console createConsole() {
    return new Console(Verbosity.STANDARD_INFORMATION, System.out, System.err, Ansi.withoutTty());
  }
}
