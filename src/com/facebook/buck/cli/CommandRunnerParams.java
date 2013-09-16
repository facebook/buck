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
import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

import java.util.regex.Pattern;

/**
 * {@link CommandRunnerParams} is the collection of parameters needed to create a
 * {@link CommandRunner}.
 */
class CommandRunnerParams {

  private final ProjectFilesystem projectFilesystem;
  private final KnownBuildRuleTypes buildRuleTypes;
  private final ArtifactCacheFactory artifactCacheFactory;
  private final Console console;
  private final Parser parser;
  private final BuckEventBus eventBus;
  private final Platform platform;

  @VisibleForTesting
  public CommandRunnerParams(
      Console console,
      ProjectFilesystem projectFilesystem,
      KnownBuildRuleTypes buildRuleTypes,
      ArtifactCacheFactory artifactCacheFactory,
      BuckEventBus eventBus,
      String pythonInterpreter,
      Platform platform) {
    this(console,
        projectFilesystem,
        buildRuleTypes,
        artifactCacheFactory,
        eventBus,
        new Parser(projectFilesystem,
            buildRuleTypes,
            console,
            pythonInterpreter,
            ImmutableSet.<Pattern>of() /* Temp file patterns */),
        platform);
  }

  public CommandRunnerParams(
      Console console,
      ProjectFilesystem projectFilesystem,
      KnownBuildRuleTypes buildRuleTypes,
      ArtifactCacheFactory artifactCacheFactory,
      BuckEventBus eventBus,
      Parser parser,
      Platform platform) {
    this.console = Preconditions.checkNotNull(console);
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.buildRuleTypes = Preconditions.checkNotNull(buildRuleTypes);
    this.artifactCacheFactory = Preconditions.checkNotNull(artifactCacheFactory);
    this.eventBus = Preconditions.checkNotNull(eventBus);
    this.parser = Preconditions.checkNotNull(parser);
    this.platform = Preconditions.checkNotNull(platform);
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

  public ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
  }

  public KnownBuildRuleTypes getBuildRuleTypes() {
    return buildRuleTypes;
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

  public Platform getPlatform() {
    return platform;
  }
}
