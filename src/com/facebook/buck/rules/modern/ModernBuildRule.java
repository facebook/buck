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

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.keys.AlterRuleKeys;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.rules.modern.impl.DefaultClassInfoFactory;
import com.facebook.buck.rules.modern.impl.DefaultInputRuleResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * ModernBuildRule wraps a Buildable into something that implements BuildRule (and various other
 * interfaces used by the build engine). Most of the overridden methods from
 * BuildRule/AbstractBuildRule are intentionally final to keep users on the safe path.
 *
 * <p>Deps, outputs and rulekeys are derived from the fields of the {@link Buildable}.
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
public class ModernBuildRule<T extends Buildable>
    implements BuildRule,
        HasRuntimeDeps,
        SupportsInputBasedRuleKey,
        InitializableFromDisk<ModernBuildRule.DataHolder> {
  private final BuildTarget buildTarget;
  private final ProjectFilesystem filesystem;
  private final InputRuleResolver inputRuleResolver;
  private final BuildOutputInitializer<DataHolder> buildOutputInitializer;
  private final OutputPathResolver outputPathResolver;

  private final Supplier<ImmutableSortedSet<BuildRule>> deps;
  private final T buildable;
  private final ClassInfo<T> classInfo;

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
    this.filesystem = filesystem;
    this.buildTarget = buildTarget;
    this.deps = MoreSuppliers.memoize(this::computeDeps);
    this.inputRuleResolver = new DefaultInputRuleResolver(ruleFinder);
    this.buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);
    this.outputPathResolver =
        new DefaultOutputPathResolver(this.getProjectFilesystem(), this.getBuildTarget());
    this.buildable = buildableSource.transform(b -> b, clz -> clz.cast(this));
    this.classInfo = DefaultClassInfoFactory.forBuildable(this.buildable);
  }

  private ImmutableSortedSet<BuildRule> computeDeps() {
    ImmutableSortedSet.Builder<BuildRule> depsBuilder = ImmutableSortedSet.naturalOrder();
    classInfo.computeDeps(buildable, inputRuleResolver, depsBuilder::add);
    return depsBuilder.build();
  }

  protected final T getBuildable() {
    return buildable;
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }

  // -----------------------------------------------------------------------------------------------
  // ---------- These function's behaviors can be changed with interfaces on the Buildable ---------
  // -----------------------------------------------------------------------------------------------

  @Override
  public final boolean isCacheable() {
    // Uses instanceof to force this to be non-dynamic.
    return !(buildable instanceof HasBrokenCaching);
  }

  @Override
  public final boolean inputBasedRuleKeyIsEnabled() {
    // Uses instanceof to force this to be non-dynamic.
    return !(buildable instanceof HasBrokenInputBasedRuleKey);
  }

  // -----------------------------------------------------------------------------------------------
  // ---------------------- Everything below here is intentionally final ---------------------------
  // -----------------------------------------------------------------------------------------------

  public static final class DataHolder {
    // TODO(cjhopman): implement initialize from disk stuff
  }

  /**
   * This should only be exposed to implementations of the ModernBuildRule, not of the Buildable.
   */
  protected final SourcePath getSourcePath(OutputPath outputPath) {
    // TODO(cjhopman): enforce that the outputPath is actually from this target somehow.
    return ExplicitBuildTargetSourcePath.of(
        buildTarget, outputPathResolver.resolvePath(outputPath));
  }

  @Override
  public final ImmutableSortedSet<BuildRule> getBuildDeps() {
    return deps.get();
  }

  @Override
  public final String getType() {
    return classInfo.getType();
  }

  @Override
  public final ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> stepBuilder = ImmutableList.builder();
    stepBuilder.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), filesystem, outputPathResolver.getRootPath())));

    stepBuilder.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), filesystem, outputPathResolver.getTempPath())));

    stepBuilder.addAll(
        buildable.getBuildSteps(
            context,
            filesystem,
            outputPathResolver,
            new DefaultBuildCellRelativePathFactory(
                context.getBuildCellRootPath(), filesystem, Optional.of(outputPathResolver))));

    // TODO(cjhopman): Should this delete the scratch directory? Maybe delete by default but
    // preserve it based on verbosity. Currently, since CachingBuildEngine doesn't know what files
    // may have been created by a BuildRule, it can't reliably clear out old state when building a
    // rule. With ModernBuildRule, all outputs are limited to the gen/scratch roots which are unique
    // to the rule and so the engine could reliably clean old state and then leaving the scratch
    // directory would be fine.
    buildableContext.recordArtifact(outputPathResolver.getRootPath());
    // All the outputs are already forced to be within getGenDirectory(), and so this recording
    // isn't actually necessary.
    classInfo.getOutputs(buildable, (name, output) -> recordOutput(buildableContext, output));
    return stepBuilder.build();
  }

  private void recordOutput(BuildableContext buildableContext, OutputPath output) {
    buildableContext.recordArtifact(outputPathResolver.resolvePath(output));
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
  public final DataHolder initializeFromDisk() throws IOException {
    // TODO(cjhopman): implement
    return new DataHolder();
  }

  @Override
  public final BuildOutputInitializer<DataHolder> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  @Override
  public final Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    // TODO(cjhopman): implement
    return Stream.of();
  }

  @Override
  public final BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public final String getFullyQualifiedName() {
    return buildTarget.getFullyQualifiedName();
  }

  @Override
  public final ProjectFilesystem getProjectFilesystem() {
    return filesystem;
  }

  @Override
  public final int compareTo(BuildRule that) {
    if (this == that) {
      return 0;
    }

    return this.getBuildTarget().compareTo(that.getBuildTarget());
  }

  @Override
  public final boolean equals(Object obj) {
    if (!(obj instanceof ModernBuildRule)) {
      return false;
    }
    if (this.getClass() != obj.getClass()) {
      return false;
    }
    ModernBuildRule<?> that = (ModernBuildRule<?>) obj;
    return (this.classInfo == that.classInfo) && Objects.equals(this.buildTarget, that.buildTarget);
  }

  @Override
  public final int hashCode() {
    return this.buildTarget.hashCode();
  }

  @Override
  public final String toString() {
    return getFullyQualifiedName();
  }
}
