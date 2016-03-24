/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.FileScrubberStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * A {@link com.facebook.buck.rules.BuildRule} which builds an "ar" archive from input files
 * represented as {@link com.facebook.buck.rules.SourcePath}.
 */
public class Archive extends AbstractBuildRule implements SupportsInputBasedRuleKey {

  @AddToRuleKey
  private final Archiver archiver;
  @AddToRuleKey
  private final Tool ranlib;
  @AddToRuleKey(stringify = true)
  private final Path output;
  @AddToRuleKey
  private final ImmutableList<SourcePath> inputs;

  private Archive(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Archiver archiver,
      Tool ranlib,
      Path output,
      ImmutableList<SourcePath> inputs) {
    super(params, resolver);
    this.archiver = archiver;
    this.ranlib = ranlib;
    this.output = output;
    this.inputs = inputs;
  }

  /**
   * Construct an {@link com.facebook.buck.cxx.Archive} from a
   * {@link com.facebook.buck.rules.BuildRuleParams} object representing a target
   * node.  In particular, make sure to trim dependencies to *only* those that
   * provide the input {@link com.facebook.buck.rules.SourcePath}.
   */
  public static Archive from(
      BuildTarget target,
      BuildRuleParams baseParams,
      SourcePathResolver resolver,
      Archiver archiver,
      Tool ranlib,
      Path output,
      ImmutableList<SourcePath> inputs) {

    // Convert the input build params into ones specialized for this archive build rule.
    // In particular, we only depend on BuildRules directly from the input file SourcePaths.
    BuildRuleParams archiveParams =
        baseParams.copyWithChanges(
            target,
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(resolver.filterBuildRuleInputs(inputs))
                    .addAll(archiver.getDeps(resolver))
                    .build()));

    return new Archive(
        archiveParams,
        resolver,
        archiver,
        ranlib,
        output,
        inputs);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    // Cache the archive we built.
    buildableContext.recordArtifact(output);

    return ImmutableList.of(
        new MkdirStep(getProjectFilesystem(), output.getParent()),
        new RmStep(getProjectFilesystem(), output, /* shouldForceDeletion */ true),
        new ArchiveStep(
            getProjectFilesystem(),
            archiver.getEnvironment(getResolver()),
            archiver.getCommandPrefix(getResolver()),
            output,
            getResolver().getAllAbsolutePaths(inputs)),
        new ShellStep(getProjectFilesystem().getRootPath()) {
          @Override
          protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
            return ImmutableList.<String>builder()
                .addAll(ranlib.getCommandPrefix(getResolver()))
                .add(output.toString())
                .build();
          }

          @Override
          public String getShortName() {
            return "ranlib";
          }
        },
        new FileScrubberStep(getProjectFilesystem(), output, archiver.getScrubbers()));
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

}
