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

package com.facebook.buck.android;

import com.facebook.buck.java.AccumulateClassNames;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import javax.annotation.Nullable;

/**
 * {@link DexProducedFromJavaLibraryThatContainsClassFiles} is a {@link Buildable} that serves a
 * very specific purpose: it takes a {@link JavaLibraryRule} and the list of classes in the
 * {@link JavaLibraryRule} (which is represented by an {@link AccumulateClassNames}), and dexes the
 * output of the {@link JavaLibraryRule} if its list of classes is non-empty. Because it is
 * expected to be used with pre-dexing, we always pass the {@code --force-jumbo} flag to {@code dx}
 * in this buildable.
 * <p>
 * Most {@link Buildable}s can determine the (possibly null) path to their output file from their
 * definition. This is an anomaly because we do not know whether this will write a {@code .dex} file
 * until runtime. Unfortunately, because there is no such thing as an empty {@code .dex} file, we
 * cannot write a meaningful "dummy .dex" if there are no class files to pass to {@code dx}.
 */
public class DexProducedFromJavaLibraryThatContainsClassFiles extends AbstractBuildable {

  private final BuildTarget buildTarget;
  private final AccumulateClassNames javaLibraryWithClassesList;

  @VisibleForTesting
  DexProducedFromJavaLibraryThatContainsClassFiles(BuildTarget buildTarget,
      AccumulateClassNames javaLibraryWithClassesList) {
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
    this.javaLibraryWithClassesList = Preconditions.checkNotNull(javaLibraryWithClassesList);
  }

  @Override
  public Iterable<String> getInputsToCompareToOutput() {
    // The deps of this rule already capture all of the inputs that should affect the cache key.
    return ImmutableList.of();
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, final BuildableContext buildableContext)
      throws IOException {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new RmStep(getPathToDex().toString(), /* shouldForceDeletion */ true));

    // Make sure that the buck-out/gen/ directory exists for this.buildTarget.
    steps.add(new MkdirStep(getPathToDex().getParent()));

    if (!javaLibraryWithClassesList.getClassNames().isEmpty()) {
      // To be conservative, use --force-jumbo for these intermediate .dex files so that they can be
      // merged into a final classes.dex that uses jumbo instructions.
      JavaLibraryRule javaLibraryRuleToDex = javaLibraryWithClassesList.getJavaLibraryRule();
      DxStep dx = new DxStep(getPathToDex().toString(),
          Collections.singleton(Paths.get(javaLibraryRuleToDex.getPathToOutputFile())),
          EnumSet.of(DxStep.Option.NO_OPTIMIZE, DxStep.Option.FORCE_JUMBO));
      steps.add(dx);

      AbstractExecutionStep recordArtifactStep = new AbstractExecutionStep("record_dx_success") {
        @Override
        public int execute(ExecutionContext context) {
          buildableContext.recordArtifact(getPathToDex().getFileName());
          return 0;
        }
      };
      steps.add(recordArtifactStep);
    }

    return steps.build();
  }

  @Override
  @Nullable
  public String getPathToOutputFile() {
    // A .dex file is not guaranteed to be generated, so we return null to be conservative.
    return null;
  }

  public Path getPathToDex() {
    return Paths.get(
        BuckConstant.GEN_DIR,
        buildTarget.getBasePath(),
        buildTarget.getShortName() + ".dex.jar");
  }

  public boolean hasOutput() {
    // TODO(mbolin): Assert that this Buildable has been built. Currently, there is no way to do
    // that from a Buildable (but there is from an AbstractCachingBuildRule).
    return !javaLibraryWithClassesList.getClassNames().isEmpty();
  }

  public static Builder newPreDexBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildable.Builder {

    private AccumulateClassNames javaLibraryWithClassesList;

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    protected BuildRuleType getType() {
      return BuildRuleType._PRE_DEX;
    }

    @Override
    protected DexProducedFromJavaLibraryThatContainsClassFiles newBuildable(
        BuildRuleParams params, BuildRuleResolver resolver) {
      return new DexProducedFromJavaLibraryThatContainsClassFiles(params.getBuildTarget(),
          javaLibraryWithClassesList);
    }

    public Builder setPathToClassNamesList(AccumulateClassNames javaLibraryWithClassesList) {
      this.javaLibraryWithClassesList = javaLibraryWithClassesList;
      return this;
    }
  }
}
