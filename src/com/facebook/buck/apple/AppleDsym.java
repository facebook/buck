/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasPostBuildSteps;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MoveStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SortedSet;
import java.util.stream.Stream;

/** Creates dSYM bundle for the given _unstripped_ binary. */
public class AppleDsym extends AbstractBuildRule
    implements HasPostBuildSteps, SupportsInputBasedRuleKey {

  public static final Flavor RULE_FLAVOR = InternalFlavor.of("apple-dsym");
  public static final String DSYM_DWARF_FILE_FOLDER = "Contents/Resources/DWARF/";

  @AddToRuleKey private final Tool lldb;

  @AddToRuleKey private final Tool dsymutil;
  @AddToRuleKey private final ImmutableList<String> dsymutilExtraFlags;

  @AddToRuleKey private final Optional<Tool> dwarfdump;
  @AddToRuleKey private final boolean verifyDsym;

  @AddToRuleKey private final SourcePath unstrippedBinarySourcePath;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> additionalSymbolDeps;

  @AddToRuleKey(stringify = true)
  private final Path dsymOutputPath;

  private BuildableSupport.DepsSupplier depsSupplier;

  private final boolean isCacheable;

  @AddToRuleKey private final boolean withDownwardApi;
  @AddToRuleKey private final Optional<String> osoPrefix;

  public AppleDsym(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder sourcePathRuleFinder,
      Tool dsymutil,
      ImmutableList<String> dsymutilExtraFlags,
      boolean verifyDsym,
      Optional<Tool> dwarfdump,
      Tool lldb,
      SourcePath unstrippedBinarySourcePath,
      ImmutableSortedSet<SourcePath> additionalSymbolDeps,
      Path dsymOutputPath,
      Optional<String> osoPrefix,
      boolean isCacheable,
      boolean withDownwardApi) {
    super(buildTarget, projectFilesystem);
    Preconditions.checkArgument(
        !verifyDsym || dwarfdump.isPresent(),
        "Requested verification of dSYM but missing dwarfdump tool");
    this.dsymutil = dsymutil;
    this.dsymutilExtraFlags = dsymutilExtraFlags;
    this.dwarfdump = dwarfdump;
    this.verifyDsym = verifyDsym;
    this.lldb = lldb;
    this.unstrippedBinarySourcePath = unstrippedBinarySourcePath;
    this.additionalSymbolDeps = additionalSymbolDeps;
    this.dsymOutputPath = dsymOutputPath;
    this.osoPrefix = osoPrefix;
    this.isCacheable = isCacheable;
    this.depsSupplier = BuildableSupport.buildDepsSupplier(this, sourcePathRuleFinder);
    this.withDownwardApi = withDownwardApi;
    checkFlavorCorrectness(buildTarget);
  }

  /** Output path for .dsym file. */
  public static RelPath getDsymOutputPath(BuildTarget target, ProjectFilesystem filesystem) {
    AppleDsym.checkFlavorCorrectness(target);
    return BuildTargetPaths.getGenPath(
        filesystem.getBuckPaths(), target, "%s." + AppleBundleExtension.DSYM.fileExtension);
  }

  public static String getDwarfFilenameForDsymTarget(BuildTarget dsymTarget) {
    AppleDsym.checkFlavorCorrectness(dsymTarget);
    return dsymTarget.getShortName();
  }

  private static void checkFlavorCorrectness(BuildTarget buildTarget) {
    Preconditions.checkArgument(
        buildTarget.getFlavors().contains(RULE_FLAVOR),
        "Rule %s must be identified by %s flavor",
        buildTarget,
        RULE_FLAVOR);
    Preconditions.checkArgument(
        !AppleDebugFormat.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors().getSet()),
        "Rule %s must not contain any debug format flavors (%s), only rule flavor %s",
        buildTarget,
        AppleDebugFormat.FLAVOR_DOMAIN.getFlavors(),
        RULE_FLAVOR);
    Preconditions.checkArgument(
        !buildTarget.getFlavors().contains(CxxStrip.RULE_FLAVOR),
        "Rule %s must not contain strip flavor %s: %s works only with unstripped binaries!",
        buildTarget,
        CxxStrip.RULE_FLAVOR,
        AppleDsym.class.toString());
    Preconditions.checkArgument(
        !StripStyle.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors().getSet()),
        "Rule %s must not contain strip style flavors: %s works only with unstripped binaries!",
        buildTarget,
        AppleDsym.class.toString());
    Preconditions.checkArgument(
        !LinkerMapMode.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors().getSet()),
        "Rule %s must not contain linker map mode flavors.",
        buildTarget);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(dsymOutputPath);

    AbsPath unstrippedBinaryPath =
        context.getSourcePathResolver().getAbsolutePath(unstrippedBinarySourcePath);
    Path dwarfFileFolder = dsymOutputPath.resolve(DSYM_DWARF_FILE_FOLDER);

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), dsymOutputPath),
            true));

    RelPath cellPath =
        ProjectFilesystemUtils.relativize(
            getProjectFilesystem().getRootPath(), context.getBuildCellRootPath());

    steps.add(
        new DsymStep(
            getProjectFilesystem(),
            dsymutil.getEnvironment(context.getSourcePathResolver()),
            dsymutil.getCommandPrefix(context.getSourcePathResolver()),
            dsymutilExtraFlags,
            verifyDsym /* includePapertrail */,
            unstrippedBinaryPath.getPath(),
            dsymOutputPath,
            cellPath,
            osoPrefix,
            withDownwardApi));

    Path dwarfFilePath = dwarfFileFolder.resolve(unstrippedBinaryPath.getFileName());

    if (verifyDsym && dwarfdump.isPresent()) {
      RelPath warningsOutputPath =
          BuildTargetPaths.getScratchPath(
              getProjectFilesystem(), this.getBuildTarget(), "%s_dwarfdump_warnings.txt");
      steps.add(
          new AppleDsymExtractPapertrailStep(
              getProjectFilesystem(),
              dwarfdump.get().getEnvironment(context.getSourcePathResolver()),
              dwarfdump.get().getCommandPrefix(context.getSourcePathResolver()),
              dwarfFilePath,
              warningsOutputPath.getPath(),
              cellPath,
              withDownwardApi));

      steps.add(
          new AbstractExecutionStep("verify-dsym") {
            @Override
            public StepExecutionResult execute(StepExecutionContext context)
                throws IOException, InterruptedException {
              AbsPath warningsPath = getProjectFilesystem().resolve(warningsOutputPath);
              try (Stream<String> stream = Files.lines(warningsPath.getPath())) {
                Optional<String> lineWithObjectError =
                    stream
                        .filter(
                            l ->
                                l.contains("unable to open object file: No such file or directory"))
                        .findFirst();
                if (lineWithObjectError.isPresent()) {
                  // Unfortunately, the warnings that dsymutil stores do not the object file names,
                  // so there's nothing else useful we can print
                  throw new HumanReadableException(
                      "dSYM verification failed, missing object files when looking up N_OSO entries");
                }
              }

              return StepExecutionResults.SUCCESS;
            }
          });
    }

    steps.add(
        new MoveStep(
            getProjectFilesystem(),
            dwarfFilePath,
            dwarfFileFolder.resolve(getDwarfFilenameForDsymTarget(getBuildTarget()))));

    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), dsymOutputPath);
  }

  @Override
  public ImmutableList<Step> getPostBuildSteps(BuildContext context) {
    return ImmutableList.of(
        new RegisterDebugSymbolsStep(
            getProjectFilesystem(),
            unstrippedBinarySourcePath,
            lldb,
            context.getSourcePathResolver(),
            dsymOutputPath,
            withDownwardApi));
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return depsSupplier.get();
  }

  @Override
  public void updateBuildRuleResolver(BuildRuleResolver ruleResolver) {
    this.depsSupplier = BuildableSupport.buildDepsSupplier(this, ruleResolver);
  }

  @Override
  public boolean isCacheable() {
    return isCacheable;
  }
}
