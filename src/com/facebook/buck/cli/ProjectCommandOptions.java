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
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nullable;

public class ProjectCommandOptions extends AbstractCommandOptions {

  public enum Ide {
    INTELLIJ,
    XCODE;

    public static Ide fromString(String string) {
      switch (Ascii.toLowerCase(string)) {
        case "intellij":
          return Ide.INTELLIJ;
        case "xcode":
          return Ide.XCODE;
        default:
          throw new HumanReadableException("Invalid ide value %s.", string);
      }
    }

  }

  private static final Ide DEFAULT_IDE_VALUE = Ide.INTELLIJ;
  private static final boolean DEFAULT_READ_ONLY_VALUE = false;

  @Option(
      name = "--combined-project",
      usage = "Generate an xcode project of a target and its dependencies.")
  private boolean combinedProject;

  @Option(name = "--process-annotations", usage = "Enable annotation processing")
  private boolean processAnnotations;

  @Option(
      name = "--with-tests",
      hidden = true)
  @SuppressWarnings("PMD.UnusedPrivateField")
  private boolean withTests = false;

  @Option(
      name = "--without-tests",
      usage = "When generating a project slice, exclude tests that test the code in that slice")
  private boolean withoutTests = false;

  @Option(
      name = "--combine-test-bundles",
      usage = "Combine multiple ios/osx test targets into the same bundle if they have identical " +
          "settings")
  private boolean combineTestBundles = false;

  @Option(
      name = "--ide",
      usage = "The type of IDE for which to generate a project. Defaults to 'intellij' if not " +
          "specified in .buckconfig.")
  @Nullable
  private Ide ide = null;

  @Option(
      name = "--read-only",
      usage = "If true, generate project files read-only. Defaults to '" +
          DEFAULT_READ_ONLY_VALUE + "' if not specified in .buckconfig. (Only " +
          "applies to generated Xcode projects.)")
  private boolean readOnly = DEFAULT_READ_ONLY_VALUE;

  @Option(
      name = "--dry-run",
      usage = "Instead of actually generating the project, only print out the targets that " +
          "would be included.")
  private boolean dryRun = false;

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

  public ImmutableSet<String> getArgumentsFormattedAsBuildTargets() {
    return ImmutableSet.copyOf(getCommandLineBuildTargetNormalizer().normalizeAll(getArguments()));
  }

  public boolean getCombinedProject() {
    return combinedProject;
  }

  public boolean getDryRun() {
    return dryRun;
  }

  public boolean getCombineTestBundles() {
    return combineTestBundles;
  }

  public boolean shouldProcessAnnotations() {
    return processAnnotations;
  }

  @VisibleForTesting
  public void setProcessAnnotations(boolean value) {
    processAnnotations = value;
  }

  public ImmutableMap<Path, String> getBasePathToAliasMap() {
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

  public ImmutableSet<String> getDefaultExcludePaths() {
    Optional<String> defaultExcludePathsPaths = getBuckConfig().getValue(
        "project", "default_exclude_paths");
    return defaultExcludePathsPaths.isPresent()
        ? ImmutableSet.<String>copyOf(
            Splitter.on(',').omitEmptyStrings().trimResults().split(defaultExcludePathsPaths.get()))
        : ImmutableSet.<String>of();
  }

  public boolean getReadOnly() {
    if (readOnly) {
      return readOnly;
    }
    return getBuckConfig().getBooleanValue("project", "read_only", DEFAULT_READ_ONLY_VALUE);
  }

  /**
   * Returns true if Buck should prompt to kill a running IDE before changing its files,
   * false otherwise.
   */
  public boolean getIdePrompt() {
    return getBuckConfig().getBooleanValue("project", "ide_prompt", true);
  }

  public Ide getIde() {
    if (ide != null) {
      return ide;
    } else {
      Optional<Ide> ide = getBuckConfig().getValue("project", "ide").transform(
          new Function<String, Ide>() {
            @Override
            public Ide apply(String input) {
              return Ide.fromString(input);
            }
          });
      return ide.or(DEFAULT_IDE_VALUE);
    }
  }

  public boolean isWithTests() {
    return !withoutTests;
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
