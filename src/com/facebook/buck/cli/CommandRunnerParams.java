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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.rules.Repository;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKey.Builder;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.util.AndroidDirectoryResolver;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.NullFileHashCache;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.regex.Pattern;

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

  @VisibleForTesting
  CommandRunnerParams(
      Console console,
      Repository repository,
      AndroidDirectoryResolver androidDirectoryResolver,
      ArtifactCacheFactory artifactCacheFactory,
      BuckEventBus eventBus,
      String pythonInterpreter,
      Platform platform,
      ImmutableMap<String, String> environment) {
    this(
        console,
        repository,
        androidDirectoryResolver,
        new CachingBuildEngine(),
        artifactCacheFactory,
        eventBus,
        new Parser(repository,
            console,
            environment,
            pythonInterpreter,
            /* tempFilePatterns */ ImmutableSet.<Pattern>of(),
            new RuleKeyBuilderFactory() {
              @Override
              public Builder newInstance(BuildRule buildRule) {
                return RuleKey.builder(buildRule, new NullFileHashCache());
              }
            }),
        platform,
        environment);
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
      ImmutableMap<String, String> environment) {
    this.console = Preconditions.checkNotNull(console);
    this.repository = Preconditions.checkNotNull(repository);
    this.buildEngine = Preconditions.checkNotNull(buildEngine);
    this.artifactCacheFactory = Preconditions.checkNotNull(artifactCacheFactory);
    this.eventBus = Preconditions.checkNotNull(eventBus);
    this.parser = Preconditions.checkNotNull(parser);
    this.platform = Preconditions.checkNotNull(platform);
    this.androidDirectoryResolver = Preconditions.checkNotNull(androidDirectoryResolver);
    this.environment = Preconditions.checkNotNull(environment);
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
}
