/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import static com.facebook.buck.zip.ZipCompressionLevel.DEFAULT_COMPRESSION_LEVEL;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.zip.ZipCompressionLevel;
import com.facebook.buck.zip.ZipStep;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

public class Javadoc extends AbstractBuildRule {

  public final static Flavor DOC_JAR = ImmutableFlavor.of("doc");

  private final Path output;
  @AddToRuleKey
  private final ImmutableSet<SourcePath> sources;
  private final Path scratchDir;

  protected Javadoc(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      ImmutableSet<SourcePath> sources) {
    super(buildRuleParams, resolver);

    this.sources = sources;
    this.output = BuildTargets.getGenPath(
        getProjectFilesystem(),
        getBuildTarget(),
        String.format("%%s/%s-javadoc.jar", getBuildTarget().getShortName()));
    this.scratchDir = BuildTargets.getScratchPath(
        getProjectFilesystem(),
        getBuildTarget(),
        String.format("%%s/%s-javadoc.tmp", getBuildTarget().getShortName()));
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new MkdirStep(getProjectFilesystem(), output.getParent()));
    steps.add(new RmStep(getProjectFilesystem(), output, /* force deletion */ true));

    if (sources.isEmpty()) {
      // Create an empty jar. This keeps things that want a jar happy without causing javadoc to
      // choke on there not being any sources.
      steps.add(
          new ZipStep(
              getProjectFilesystem(),
              output,
              ImmutableSet.<Path>of(),
              /* junk paths */ false,
              ZipCompressionLevel.MIN_COMPRESSION_LEVEL,
              output));
      return steps.build();
    } else {
      Path atFilePath = scratchDir.resolve("all-sources.txt");
      Path uncompressedOutputDir = scratchDir.resolve("docs");

      steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), scratchDir));
      steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), uncompressedOutputDir));
      // Write an @-file with all the source files in
      steps.add(new WriteFileStep(
          getProjectFilesystem(),
          Joiner.on("\n").join(
              sources.stream()
                  .map(getResolver()::getAbsolutePath)
                  .map(Path::toString)
                  .iterator()),
          atFilePath,
          /* can execute */ false));

      steps.add(new ShellStep(getProjectFilesystem().resolve(scratchDir)) {
        @Override
        protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
          return ImmutableList.of(
              "javadoc",
              "-Xdoclint:none",
              "-notimestamp",
              "-d", uncompressedOutputDir.getFileName().toString(),
              "@" + getProjectFilesystem().resolve(atFilePath));
        }

        @Override
        public String getShortName() {
          return "javadoc";
        }
      });
      steps.add(
          new ZipStep(
              getProjectFilesystem(),
              output,
              ImmutableSet.of(),
              /* junk paths */ false,
              DEFAULT_COMPRESSION_LEVEL,
              uncompressedOutputDir));
    }

    buildableContext.recordArtifact(output);
    return steps.build();
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }
}
