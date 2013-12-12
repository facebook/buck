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

package com.facebook.buck.java;

import static com.facebook.buck.rules.BuildableProperties.Kind.PACKAGING;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.Buildables;
import com.facebook.buck.rules.DoNotUseAbstractBuildable;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.DefaultDirectoryTraverser;
import com.facebook.buck.util.DirectoryTraverser;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;

public class JavaBinaryRule extends DoNotUseAbstractBuildable implements BinaryBuildRule,
    HasClasspathEntries {

  private final static BuildableProperties OUTPUT_TYPE = new BuildableProperties(PACKAGING);

  @Nullable
  private final String mainClass;

  @Nullable
  private final String manifestFile;

  @Nullable
  private final String metaInfDirectory;

  private final DirectoryTraverser directoryTraverser;

  JavaBinaryRule(
      BuildRuleParams buildRuleParams,
      @Nullable String mainClass,
      @Nullable String manifestFile,
      @Nullable String metaInfDirectory,
      DirectoryTraverser directoryTraverser) {
    super(buildRuleParams);
    this.mainClass = mainClass;
    this.manifestFile = manifestFile;
    this.metaInfDirectory = metaInfDirectory;

    this.directoryTraverser = Preconditions.checkNotNull(directoryTraverser);
  }

  private void addMetaInfContents(ImmutableSortedSet.Builder<String> files) {
    Buildables.addInputsToSortedSet(metaInfDirectory, files, directoryTraverser);
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException {
    ImmutableSortedSet.Builder<String> metaInfFiles = ImmutableSortedSet.naturalOrder();
    addMetaInfContents(metaInfFiles);

    return super.appendToRuleKey(builder)
        .set("mainClass", mainClass)
        .set("manifestFile", manifestFile)
        .set("metaInfDirectory", metaInfFiles.build());
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.JAVA_BINARY;
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  @Override
  public Iterable<String> getInputsToCompareToOutput() {
    // Build a sorted set so that metaInfDirectory contents are listed in a canonical order.
    ImmutableSortedSet.Builder<String> builder = ImmutableSortedSet.naturalOrder();

    if (manifestFile != null) {
      builder.add(manifestFile);
    }

    addMetaInfContents(builder);

    return builder.build();
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext) throws IOException {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    String outputDirectory = getOutputDirectory();
    Step mkdir = new MkdirStep(outputDirectory);
    commands.add(mkdir);

    ImmutableSet<String> includePaths;
    if (metaInfDirectory != null) {
      String stagingRoot = outputDirectory + "/meta_inf_staging";
      String stagingTarget = stagingRoot + "/META-INF";

      MakeCleanDirectoryStep createStagingRoot = new MakeCleanDirectoryStep(stagingRoot);
      commands.add(createStagingRoot);

      MkdirAndSymlinkFileStep link = new MkdirAndSymlinkFileStep(
          metaInfDirectory, stagingTarget);
      commands.add(link);

      includePaths = ImmutableSet.<String>builder()
          .add(stagingRoot)
          .addAll(getTransitiveClasspathEntries().values())
          .build();
    } else {
      includePaths = ImmutableSet.copyOf(getTransitiveClasspathEntries().values());
    }

    String outputFile = getOutputFile();
    Step jar = new JarDirectoryStep(outputFile, includePaths, mainClass, manifestFile);
    commands.add(jar);

    return commands.build();
  }

  @Override
  public ImmutableSetMultimap<JavaLibraryRule, String> getTransitiveClasspathEntries() {
    return Classpaths.getClasspathEntries(getDeps());
  }

  private String getOutputDirectory() {
    return String.format("%s/%s", BuckConstant.GEN_DIR, getBuildTarget().getBasePath());
  }

  @Override
  public String getPathToOutputFile() {
    return getOutputFile();
  }

  String getOutputFile() {
    return String.format("%s/%s.jar", getOutputDirectory(), getBuildTarget().getShortName());
  }

  public static Builder newJavaBinaryRuleBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  @Override
  public List<String> getExecutableCommand(ProjectFilesystem projectFilesystem) {
    Preconditions.checkState(mainClass != null,
        "Must specify a main class for %s in order to to run it.",
        getBuildTarget().getFullyQualifiedName());

    return ImmutableList.of("java", "-jar",
        projectFilesystem.getPathRelativizer().apply(getOutputFile()).toString());
  }

  public static class Builder extends AbstractBuildRuleBuilder<JavaBinaryRule> {

    private String mainClass;
    private String manifestFile;
    private String metaInfDirectory;

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public JavaBinaryRule build(BuildRuleResolver ruleResolver) {
      return new JavaBinaryRule(createBuildRuleParams(ruleResolver),
          mainClass,
          manifestFile,
          metaInfDirectory,
          new DefaultDirectoryTraverser());
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    @Override
    public Builder addDep(BuildTarget dep) {
      super.addDep(dep);
      return this;
    }

    public Builder setMainClass(String mainClass) {
      this.mainClass = mainClass;
      return this;
    }

    public Builder setManifest(String manifestFile) {
      this.manifestFile = manifestFile;
      return this;
    }

    public Builder setMetaInfDirectory(String metaInfDirectory) {
      this.metaInfDirectory = metaInfDirectory;
      return this;
    }
  }
}
