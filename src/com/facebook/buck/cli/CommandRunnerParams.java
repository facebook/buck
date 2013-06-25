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

import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.EventBus;

/**
 * {@link CommandRunnerParams} is the collection of parameters needed to create a
 * {@link CommandRunner}.
 */
class CommandRunnerParams {

  private final ArtifactCache artifactCache;
  private final ProjectFilesystem projectFilesystem;
  private final KnownBuildRuleTypes buildRuleTypes;
  private final Console console;
  private final Parser parser;
  private final EventBus eventBus;

  public CommandRunnerParams(
      Console console,
      ProjectFilesystem projectFilesystem,
      KnownBuildRuleTypes buildRuleTypes,
      ArtifactCache artifactCache,
      EventBus eventBus) {
    this(console,
        projectFilesystem,
        buildRuleTypes,
        artifactCache,
        eventBus,
        new Parser(projectFilesystem, buildRuleTypes, console));
  }

  public CommandRunnerParams(
      Console console,
      ProjectFilesystem projectFilesystem,
      KnownBuildRuleTypes buildRuleTypes,
      ArtifactCache artifactCache,
      EventBus eventBus,
      Parser parser) {
    this.console = Preconditions.checkNotNull(console);
    this.artifactCache = Preconditions.checkNotNull(artifactCache);
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.buildRuleTypes = Preconditions.checkNotNull(buildRuleTypes);
    this.eventBus = Preconditions.checkNotNull(eventBus);
    this.parser = Preconditions.checkNotNull(parser);
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

  public ArtifactCache getArtifactCache() {
    return artifactCache;
  }

  public ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
  }

  public KnownBuildRuleTypes getBuildRuleTypes() {
    return buildRuleTypes;
  }

  public Parser getParser() {
    return parser;
  }

  public EventBus getEventBus() {
    return eventBus;
  }
}
