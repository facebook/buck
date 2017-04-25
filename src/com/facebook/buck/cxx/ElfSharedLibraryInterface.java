/*
 * Copyright 2016-present Facebook, Inc.
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
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithResolver;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

/** Build a shared library interface from an ELF shared library. */
class ElfSharedLibraryInterface extends AbstractBuildRuleWithResolver
    implements SupportsInputBasedRuleKey {

  // We only care about sections relevant to dynamic linking.
  private static final ImmutableSet<String> SECTIONS =
      ImmutableSet.of(
          ".dynamic", ".dynsym", ".dynstr", ".gnu.version", ".gnu.version_d", ".gnu.version_r");

  private final SourcePathResolver pathResolver;
  @AddToRuleKey private final Tool objcopy;

  @AddToRuleKey private final SourcePath input;

  private ElfSharedLibraryInterface(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      Tool objcopy,
      SourcePath input) {
    super(buildRuleParams, resolver);
    this.pathResolver = resolver;
    this.objcopy = objcopy;
    this.input = input;
  }

  public static ElfSharedLibraryInterface from(
      BuildTarget target,
      BuildRuleParams baseParams,
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      Tool objcopy,
      SourcePath input) {
    return new ElfSharedLibraryInterface(
        baseParams
            .withBuildTarget(target)
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(objcopy.getDeps(ruleFinder))
                        .addAll(ruleFinder.filterBuildRuleInputs(input))
                        .build()),
                Suppliers.ofInstance(ImmutableSortedSet.of())),
        resolver,
        objcopy,
        input);
  }

  private Path getOutputDir() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s");
  }

  private String getSharedAbiLibraryName() {
    return pathResolver.getRelativePath(input).getFileName().toString();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    Path output = getOutputDir().resolve(getSharedAbiLibraryName());
    buildableContext.recordArtifact(output);
    return new ImmutableList.Builder<Step>()
        .addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), getOutputDir()))
        .add(
            ElfExtractSectionsStep.of(
                getProjectFilesystem(),
                objcopy.getCommandPrefix(context.getSourcePathResolver()),
                context.getSourcePathResolver().getAbsolutePath(input),
                output,
                SECTIONS))
        .add(ElfClearProgramHeadersStep.of(getProjectFilesystem(), output))
        .add(
            ElfSymbolTableScrubberStep.of(
                getProjectFilesystem(), output, /* section */ ".dynsym", /* allowMissing */ false))
        .add(
            ElfSymbolTableScrubberStep.of(
                getProjectFilesystem(), output, /* section */ ".symtab", /* allowMissing */ true))
        .add(ElfDynamicSectionScrubberStep.of(getProjectFilesystem(), output))
        .build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(
        getBuildTarget(), getOutputDir().resolve(getSharedAbiLibraryName()));
  }
}
