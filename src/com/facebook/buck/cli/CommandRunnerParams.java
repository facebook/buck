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

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessManager;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.InputStream;
import java.util.Map;

/**
 * {@link CommandRunnerParams} is the collection of parameters needed to run a {@link Command}.
 */
class CommandRunnerParams {

  private final ArtifactCache artifactCache;
  private final Console console;
  private final InputStream stdIn;
  private final ImmutableMap<String, String> environment;
  private final Parser parser;
  private final BuckEventBus eventBus;
  private final Platform platform;
  private final Supplier<AndroidPlatformTarget> androidPlatformTargetSupplier;
  private final Cell cell;
  private final JavaPackageFinder javaPackageFinder;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final Optional<ProcessManager> processManager;
  private final Optional<WebServer> webServer;
  private final BuckConfig buckConfig;
  private final FileHashCache fileHashCache;
  private final Map<ExecutionContext.ExecutorPool, ListeningExecutorService> executors;
  private final BuildEnvironmentDescription buildEnvironmentDescription;
  private final ActionGraphCache actionGraphCache;

  public CommandRunnerParams(
      Console console,
      InputStream stdIn,
      Cell cell,
      Supplier<AndroidPlatformTarget> androidPlatformTargetSupplier,
      ArtifactCache artifactCache,
      BuckEventBus eventBus,
      Parser parser,
      Platform platform,
      ImmutableMap<String, String> environment,
      JavaPackageFinder javaPackageFinder,
      ObjectMapper objectMapper,
      Clock clock,
      Optional<ProcessManager> processManager,
      Optional<WebServer> webServer,
      BuckConfig buckConfig,
      FileHashCache fileHashCache,
      Map<ExecutionContext.ExecutorPool, ListeningExecutorService> executors,
      BuildEnvironmentDescription buildEnvironmentDescription,
      ActionGraphCache actionGraphCache) {
    this.console = console;
    this.stdIn = stdIn;
    this.cell = cell;
    this.artifactCache = artifactCache;
    this.eventBus = eventBus;
    this.parser = parser;
    this.platform = platform;
    this.androidPlatformTargetSupplier = androidPlatformTargetSupplier;
    this.environment = environment;
    this.javaPackageFinder = javaPackageFinder;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.processManager = processManager;
    this.webServer = webServer;
    this.buckConfig = buckConfig;
    this.fileHashCache = fileHashCache;
    this.executors = executors;
    this.buildEnvironmentDescription = buildEnvironmentDescription;
    this.actionGraphCache = actionGraphCache;
  }

  public Console getConsole() {
    return console;
  }

  public InputStream getStdIn() {
    return stdIn;
  }

  public Cell getCell() {
    return cell;
  }

  public ArtifactCache getArtifactCache() {
    return artifactCache;
  }

  public Parser getParser() {
    return parser;
  }

  public BuckEventBus getBuckEventBus() {
    return eventBus;
  }

  public Supplier<AndroidPlatformTarget> getAndroidPlatformTargetSupplier() {
    return androidPlatformTargetSupplier;
  }

  public Platform getPlatform() {
    return platform;
  }

  public ImmutableMap<String, String> getEnvironment() {
    return environment;
  }

  public JavaPackageFinder getJavaPackageFinder() {
    return javaPackageFinder;
  }

  public ObjectMapper getObjectMapper() {
    return objectMapper;
  }

  public Clock getClock() {
    return clock;
  }

  public Optional<ProcessManager> getProcessManager() {
    return processManager;
  }

  public Optional<WebServer> getWebServer() {
    return webServer;
  }

  public BuckConfig getBuckConfig() {
    return buckConfig;
  }

  public FileHashCache getFileHashCache() {
    return fileHashCache;
  }

  public Map<ExecutionContext.ExecutorPool, ListeningExecutorService> getExecutors() {
    return executors;
  }

  public BuildEnvironmentDescription getBuildEnvironmentDescription() {
    return buildEnvironmentDescription;
  }

  public ActionGraphCache getActionGraphCache() {
    return actionGraphCache;
  }

}
