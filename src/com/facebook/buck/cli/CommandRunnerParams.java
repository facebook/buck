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
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.Repository;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessManager;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;

/**
 * {@link CommandRunnerParams} is the collection of parameters needed to create a
 * {@link CommandRunner}.
 */
class CommandRunnerParams {

  private final BuildEngine buildEngine;
  private final ArtifactCacheFactory artifactCacheFactory;
  private final Console console;
  private final ImmutableMap<String, String> environment;
  private final Parser parser;
  private final BuckEventBus eventBus;
  private final Platform platform;
  private final Supplier<AndroidPlatformTarget> androidPlatformTargetSupplier;
  private final Repository repository;
  private final JavaPackageFinder javaPackageFinder;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final Optional<ProcessManager> processManager;

  public CommandRunnerParams(
      Console console,
      Repository repository,
      Supplier<AndroidPlatformTarget> androidPlatformTargetSupplier,
      BuildEngine buildEngine,
      ArtifactCacheFactory artifactCacheFactory,
      BuckEventBus eventBus,
      Parser parser,
      Platform platform,
      ImmutableMap<String, String> environment,
      JavaPackageFinder javaPackageFinder,
      ObjectMapper objectMapper,
      Clock clock,
      Optional<ProcessManager> processManager) {
    this.console = console;
    this.repository = repository;
    this.buildEngine = buildEngine;
    this.artifactCacheFactory = artifactCacheFactory;
    this.eventBus = eventBus;
    this.parser = parser;
    this.platform = platform;
    this.androidPlatformTargetSupplier = androidPlatformTargetSupplier;
    this.environment = environment;
    this.javaPackageFinder = javaPackageFinder;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.processManager = processManager;
  }

  public Console getConsole() {
    return console;
  }

  public Repository getRepository() {
    return repository;
  }

  public ArtifactCacheFactory getArtifactCacheFactory() {
    return artifactCacheFactory;
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

  public BuildEngine getBuildEngine() {
    return buildEngine;
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
}
