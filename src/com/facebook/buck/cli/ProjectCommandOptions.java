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

import com.facebook.buck.java.JavaPackageFinder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.util.List;

public class ProjectCommandOptions extends AbstractCommandOptions {

  private static final String DEFAULT_IDE_VALUE = "intellij";
  private static final boolean DEFAULT_READ_ONLY_VALUE = false;

  @Option(
      name = "--combined-project",
      usage = "Generate an xcode project of a target and its dependencies.")
  private String combinedProject;

  @Option(
      name = "--workspace-and-projects",
      usage = "Generate an xcode workspace containing separate projects per target.")
  private boolean workspaceAndProjects;

  @Option(name = "--process-annotations", usage = "Enable annotation processing")
  private boolean processAnnotations;

  @Option(
      name = "--with-tests",
      usage = "(In Alpha) When generating a project slice, also include all tests that test " +
          "code in that slice.")
  private boolean withTests = false;

  @Option(
      name = "--ide",
      usage = "The type of IDE for which to generate a project. Defaults to '" +
          DEFAULT_IDE_VALUE + "' if not specified in .buckconfig.")
  private String ide = null;

  @Option(
      name = "--read-only",
      usage = "If true, generate project files read-only. Defaults to '" +
          DEFAULT_IDE_VALUE + "' if not specified in .buckconfig. (Only " +
          "applies to generated Xcode projects.)")
  private boolean readOnly = DEFAULT_READ_ONLY_VALUE;

  @Argument
  private List<String> arguments = Lists.newArrayList();

  ProjectCommandOptions(BuckConfig buckConfig) {
    super(buckConfig);
  }

  public List<String> getArguments() {
    return arguments;
  }

  public void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  public List<String> getArgumentsFormattedAsBuildTargets() {
    return getCommandLineBuildTargetNormalizer().normalizeAll(getArguments());
  }

  public String getCombinedProject() {
    return combinedProject;
  }

  public boolean getWorkspaceAndProjects() {
    return workspaceAndProjects;
  }

  public boolean shouldProcessAnnotations() {
    return processAnnotations;
  }

  @VisibleForTesting
  public void setProcessAnnotations(boolean value) {
    processAnnotations = value;
  }

  public ImmutableMap<String, String> getBasePathToAliasMap() {
    return getBuckConfig().getBasePathToAliasMap();
  }

  public JavaPackageFinder getJavaPackageFinder() {
    return getBuckConfig().createDefaultJavaPackageFinder();
  }

  public Optional<String> getPathToDefaultAndroidManifest() {
    return getBuckConfig().getValue("project", "default_android_manifest");
  }

  public Optional<String> getPathToPostProcessScript() {
    return getBuckConfig().getValue("project", "post_process");
  }

  public boolean getReadOnly() {
    if (readOnly) {
      return readOnly;
    }
    return getBuckConfig().getBooleanValue("project", "read_only", DEFAULT_READ_ONLY_VALUE);
  }

  public String getIde() {
    if (ide != null) {
      return ide;
    } else {
      Optional<String> ide = getBuckConfig().getValue("project", "ide");
      return ide.or(DEFAULT_IDE_VALUE);
    }
  }

  public boolean isWithTests() {
    return withTests;
  }

  private List<String> getInitialTargets() {
    Optional<String> initialTargets = getBuckConfig().getValue("project", "initial_targets");
    return initialTargets.isPresent()
        ? Lists.newArrayList(Splitter.on(' ').trimResults().split(initialTargets.get()))
        : ImmutableList.<String>of();
  }

  public boolean hasInitialTargets() {
    return !getInitialTargets().isEmpty();
  }

  public BuildCommandOptions createBuildCommandOptionsWithInitialTargets(
      List<String> additionalInitialTargets) {
    List<String> initialTargets;
    if (additionalInitialTargets.isEmpty()) {
      initialTargets = getInitialTargets();
    } else {
      initialTargets = Lists.newArrayList();
      initialTargets.addAll(getInitialTargets());
      initialTargets.addAll(additionalInitialTargets);
    }

    BuildCommandOptions buildCommandOptions = new BuildCommandOptions(getBuckConfig());
    buildCommandOptions.setArguments(initialTargets);
    return buildCommandOptions;
  }
}
