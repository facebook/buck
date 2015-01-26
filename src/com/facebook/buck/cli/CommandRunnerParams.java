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

import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.Repository;
import com.facebook.buck.rules.RepositoryFactory;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKey.Builder;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.FileHashCache;
import com.facebook.buck.util.NullFileHashCache;
import com.facebook.buck.util.ProcessManager;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;

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
  private final AndroidDirectoryResolver androidDirectoryResolver;
  private final Repository repository;
  private final JavaPackageFinder javaPackageFinder;
  private final ObjectMapper objectMapper;
  private final FileHashCache fileHashCache;
  private final Clock clock;
  private final Optional<ProcessManager> processManager;

  @VisibleForTesting
  CommandRunnerParams(
      Console console,
      RepositoryFactory repositoryFactory,
      Repository repository,
      AndroidDirectoryResolver androidDirectoryResolver,
      ArtifactCacheFactory artifactCacheFactory,
      BuckEventBus eventBus,
      ParserConfig parserConfig,
      Platform platform,
      ImmutableMap<String, String> environment,
      JavaPackageFinder javaPackageFinder,
      ObjectMapper objectMapper,
      FileHashCache fileHashCache)
      throws IOException, InterruptedException {
    this(
        console,
        repository,
        androidDirectoryResolver,
        new CachingBuildEngine(),
        artifactCacheFactory,
        eventBus,
        Parser.createParser(
            repositoryFactory,
            parserConfig,
            new RuleKeyBuilderFactory() {
              @Override
              public Builder newInstance(BuildRule buildRule, SourcePathResolver resolver) {
                return RuleKey.builder(buildRule, resolver, new NullFileHashCache());
              }
            }),
        platform,
        environment,
        javaPackageFinder,
        objectMapper,
        fileHashCache,
        new DefaultClock(),
        Optional.<ProcessManager>absent());
  }

  public CommandRunnerParams(
      Console console,
      Repository repository,
      AndroidDirectoryResolver androidDirectoryResolver,
      BuildEngine buildEngine,
      ArtifactCacheFactory artifactCacheFactory,
      BuckEventBus eventBus,
      Parser parser,
      Platform platform,
      ImmutableMap<String, String> environment,
      JavaPackageFinder javaPackageFinder,
      ObjectMapper objectMapper,
      FileHashCache fileHashCache,
      Clock clock,
      Optional<ProcessManager> processManager) {
    this.console = console;
    this.repository = repository;
    this.buildEngine = buildEngine;
    this.artifactCacheFactory = artifactCacheFactory;
    this.eventBus = eventBus;
    this.parser = parser;
    this.platform = platform;
    this.androidDirectoryResolver = androidDirectoryResolver;
    this.environment = environment;
    this.javaPackageFinder = javaPackageFinder;
    this.objectMapper = objectMapper;
    this.fileHashCache = fileHashCache;
    this.clock = clock;
    this.processManager = processManager;
  }

  public Ansi getAnsi() {
    return console.getAnsi();
  }

  public Console getConsole() {
    return console;
  }

  public Verbosity getVerbosity() {
    return console.getVerbosity();
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

  public AndroidDirectoryResolver getAndroidDirectoryResolver() {
    return androidDirectoryResolver;
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

  public FileHashCache getFileHashCache() {
    return fileHashCache;
  }

  public Clock getClock() {
    return clock;
  }

  public Optional<ProcessManager> getProcessManager() {
    return processManager;
  }
}
