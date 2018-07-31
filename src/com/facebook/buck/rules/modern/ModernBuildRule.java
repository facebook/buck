/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.modern;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKeyObjectSink;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.keys.AlterRuleKeys;
import com.facebook.buck.rules.modern.impl.DefaultClassInfoFactory;
import com.facebook.buck.rules.modern.impl.DefaultInputRuleResolver;
import com.facebook.buck.rules.modern.impl.DepsComputingVisitor;
import com.facebook.buck.rules.modern.impl.InputsVisitor;
import com.facebook.buck.rules.modern.impl.OutputPathVisitor;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * ModernBuildRule wraps a Buildable into something that implements BuildRule (and various other
 * interfaces used by the build engine). Most of the overridden methods from
 * BuildRule/AbstractBuildRule are intentionally final to keep users on the safe path.
 *
 * <p>Deps, outputs, inputs and rulekeys are derived from the fields of the {@link Buildable}.
 *
 * <p>For simple ModernBuildRules (e.g. those that don't have any fields that can't be added to the
 * rulekey), the build rule class itself can (and should) implement Buildable. In this case, the
 * constructor taking a {@code Class<T>} should be used.
 *
 * <p>Example:
 *
 * <pre>{@code
 * class WriteData extends ModernBuildRule<WriteData> implements Buildable {
 *   final String data;
 *   public WriteData(
 *       SourcePathRuleFinder ruleFinder,
 *       BuildTarget buildTarget,
 *       ProjectFilesystem projectFilesystem,
 *       String data) {
 *     super(buildTarget, projectFilesystem, ruleFinder, WriteData.class);
 *     this.data = data;
 *   }
 *
 *   ...
 * }
 * }</pre>
 *
 * <p>Some BuildRules contain more information than just that added to the rulekey and used for
 * getBuildSteps(). For these rules, the part used for getBuildSteps should be split out into its
 * own implementation of Buildable.
 *
 * <p>Example:
 *
 * <pre>{@code
 * class CopyData extends ModernBuildRule<CopyData.Impl> implements Buildable {
 *   BuildRule other;
 *   public WriteData(
 *       SourcePathRuleFinder ruleFinder,
 *       BuildTarget buildTarget,
 *       ProjectFilesystem projectFilesystem,
 *       BuildRule other) {
 *     super(buildTarget, projectFilesystem, ruleFinder, new Impl("hello"));
 *     this.other = other;
 *   }
 *
 *   private static class Impl implements Buildable {
 *     ...
 *   }
 *   ...
 * }
 * }</pre>
 */
public class ModernBuildRule<T extends Buildable> extends AbstractBuildRule
    implements SupportsInputBasedRuleKey {
  private final OutputPathResolver outputPathResolver;

  private final Supplier<ImmutableSortedSet<BuildRule>> deps;
  private final T buildable;
  private final ClassInfo<T> classInfo;

  private InputRuleResolver inputRuleResolver;

  protected ModernBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder finder,
      Class<T> clazz) {
    this(buildTarget, filesystem, Either.ofRight(clazz), finder);
  }

  protected ModernBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder ruleFinder,
      T buildable) {
    this(buildTarget, filesystem, Either.ofLeft(buildable), ruleFinder);
  }

  private ModernBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      Either<T, Class<T>> buildableSource,
      SourcePathRuleFinder ruleFinder) {
    super(buildTarget, filesystem);
    this.deps = MoreSuppliers.memoize(this::computeDeps);
    this.inputRuleResolver = new DefaultInputRuleResolver(ruleFinder);
    this.outputPathResolver =
        new DefaultOutputPathResolver(this.getProjectFilesystem(), this.getBuildTarget());
    this.buildable = buildableSource.transform(b -> b, clz -> clz.cast(this));
    this.classInfo = DefaultClassInfoFactory.forInstance(this.buildable);
  }

  private ImmutableSortedSet<BuildRule> computeDeps() {
    ImmutableSortedSet.Builder<BuildRule> depsBuilder = ImmutableSortedSet.naturalOrder();
    classInfo.visit(buildable, new DepsComputingVisitor(inputRuleResolver, depsBuilder::add));
    return depsBuilder.build();
  }

  /** Computes the inputs of the build rule. */
  @SuppressWarnings("unused")
  public ImmutableSortedSet<SourcePath> computeInputs() {
    return computeInputs(getBuildable(), classInfo);
  }

  /** Computes the inputs of the build rule. */
  public static <T extends Buildable> ImmutableSortedSet<SourcePath> computeInputs(
      T buildable, ClassInfo<T> classInfo) {
    ImmutableSortedSet.Builder<SourcePath> depsBuilder = ImmutableSortedSet.naturalOrder();
    classInfo.visit(buildable, new InputsVisitor(depsBuilder::add));
    return depsBuilder.build();
  }

  public final T getBuildable() {
    return buildable;
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }

  /**
   * This field could be used unsafely, most ModernBuildRule should never need this directly and it
   * should only be used within the getBuildSteps() call.
   */
  public OutputPathResolver getOutputPathResolver() {
    return outputPathResolver;
  }

  @Override
  public void updateBuildRuleResolver(
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver) {
    this.inputRuleResolver = new DefaultInputRuleResolver(ruleFinder);
  }

  // -----------------------------------------------------------------------------------------------
  // ---------- These function's behaviors can be changed with interfaces on the Buildable ---------
  // -----------------------------------------------------------------------------------------------

  @Override
  public final boolean inputBasedRuleKeyIsEnabled() {
    // Uses instanceof to force this to be non-dynamic.
    return !(buildable instanceof HasBrokenInputBasedRuleKey);
  }

  // -----------------------------------------------------------------------------------------------
  // ---------------------- Everything below here is intentionally final ---------------------------
  // -----------------------------------------------------------------------------------------------

  /**
   * This should only be exposed to implementations of the ModernBuildRule, not of the Buildable.
   */
  protected final BuildTargetSourcePath getSourcePath(OutputPath outputPath) {
    // TODO(cjhopman): enforce that the outputPath is actually from this target somehow.
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(), outputPathResolver.resolvePath(outputPath));
  }

  @Override
  public final ImmutableSortedSet<BuildRule> getBuildDeps() {
    return deps.get();
  }

  @Override
  public final ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Path> outputsBuilder = ImmutableList.builder();
    recordOutputs(outputsBuilder::add);
    ImmutableList<Path> outputs = outputsBuilder.build();
    outputs.forEach(buildableContext::recordArtifact);
    return stepsForBuildable(context, buildable, getProjectFilesystem(), getBuildTarget(), outputs);
  }

  /**
   * Returns the build steps for the Buildable. Unlike getBuildSteps(), this does not record outputs
   * (callers should call recordOutputs() themselves).
   */
  public static <T extends Buildable> ImmutableList<Step> stepsForBuildable(
      BuildContext context,
      T buildable,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      Iterable<Path> outputs) {
    ImmutableList.Builder<Step> stepBuilder = ImmutableList.builder();
    OutputPathResolver outputPathResolver = new DefaultOutputPathResolver(filesystem, buildTarget);

    // TODO(cjhopman): This should probably actually be handled by the build engine.
    for (Path output : outputs) {
      stepBuilder.add(
          RmStep.of(
                  BuildCellRelativePath.fromCellRelativePath(
                      context.getBuildCellRootPath(), filesystem, output))
              .withRecursive(true));
    }

    BuildCellRelativePath rootPath =
        BuildCellRelativePath.fromCellRelativePath(
            context.getBuildCellRootPath(), filesystem, outputPathResolver.getRootPath());
    BuildCellRelativePath tempPath =
        BuildCellRelativePath.fromCellRelativePath(
            context.getBuildCellRootPath(), filesystem, outputPathResolver.getTempPath());

    stepBuilder.addAll(MakeCleanDirectoryStep.of(rootPath));
    stepBuilder.addAll(MakeCleanDirectoryStep.of(tempPath));

    stepBuilder.addAll(
        buildable.getBuildSteps(
            context,
            filesystem,
            outputPathResolver,
            new DefaultBuildCellRelativePathFactory(
                context.getBuildCellRootPath(), filesystem, Optional.of(outputPathResolver))));

    // TODO(cjhopman): This should probably be handled by the build engine.
    if (context.getShouldDeleteTemporaries()) {
      stepBuilder.add(RmStep.of(tempPath).withRecursive(true));
    }

    return stepBuilder.build();
  }

  /** Return the steps for a buildable. */
  public static <T extends Buildable> ImmutableList<Step> stepsForBuildable(
      BuildContext context, T buildable, ProjectFilesystem filesystem, BuildTarget buildTarget) {
    ImmutableList.Builder<Path> outputs = ImmutableList.builder();
    recordOutputs(
        outputs::add,
        new DefaultOutputPathResolver(filesystem, buildTarget),
        DefaultClassInfoFactory.forInstance(buildable),
        buildable);
    return stepsForBuildable(context, buildable, filesystem, buildTarget, outputs.build());
  }

  /**
   * Records the outputs of this buildrule. An output will only be recorded once (i.e. no duplicates
   * and if a directory is recorded, none of its contents will be).
   */
  public void recordOutputs(BuildableContext buildableContext) {
    recordOutputs(buildableContext, outputPathResolver, classInfo, buildable);
  }

  /**
   * Records the outputs of this buildrule. An output will only be recorded once (i.e. no duplicates
   * and if a directory is recorded, none of its contents will be).
   */
  public static <T extends Buildable> void recordOutputs(
      BuildableContext buildableContext,
      OutputPathResolver outputPathResolver,
      ClassInfo<T> classInfo,
      T buildable) {
    Stream.Builder<Path> collector = Stream.builder();
    collector.add(outputPathResolver.getRootPath());
    classInfo.visit(
        buildable,
        new OutputPathVisitor(
            path1 -> {
              // Check that any PublicOutputPath is not specified inside the rule's temporary
              // directory,
              // as the temp directory may be deleted after the rule is run.
              if (path1 instanceof PublicOutputPath
                  && path1.getPath().startsWith(outputPathResolver.getTempPath())) {
                throw new IllegalStateException(
                    "PublicOutputPath should not be inside rule temporary directory. Path: "
                        + path1);
              }
              collector.add(outputPathResolver.resolvePath(path1));
            }));
    // ImmutableSet guarantees that iteration order is unchanged.
    Set<Path> outputs = collector.build().collect(ImmutableSet.toImmutableSet());
    for (Path path : outputs) {
      Preconditions.checkState(!path.isAbsolute());
      if (shouldRecord(outputs, path)) {
        buildableContext.recordArtifact(path);
      }
    }
  }

  private static boolean shouldRecord(Set<Path> outputs, Path path) {
    Path parent = path.getParent();
    while (parent != null) {
      if (outputs.contains(parent)) {
        return false;
      }
      parent = parent.getParent();
    }
    return true;
  }

  @Override
  public final boolean outputFileCanBeCopied() {
    return false;
  }

  @Override
  public final void appendToRuleKey(RuleKeyObjectSink sink) {
    AlterRuleKeys.amendKey(sink, buildable);
  }

  @Override
  public final int compareTo(BuildRule that) {
    if (this == that) {
      return 0;
    }

    return this.getBuildTarget().compareTo(that.getBuildTarget());
  }
}
