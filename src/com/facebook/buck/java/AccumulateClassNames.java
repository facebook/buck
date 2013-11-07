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

package com.facebook.buck.java;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * {@link Buildable} that writes the list of {@code .class} files found in a zip or directory to a
 * file.
 */
public class AccumulateClassNames extends AbstractBuildable {

  private final JavaLibraryRule javaLibraryRule;
  private final Path pathToOutputFile;

  private AccumulateClassNames(BuildTarget buildTarget, JavaLibraryRule javaLibraryRule) {
    Preconditions.checkNotNull(buildTarget);
    this.javaLibraryRule = Preconditions.checkNotNull(javaLibraryRule);
    this.pathToOutputFile = Paths.get(
        BuckConstant.GEN_DIR,
        buildTarget.getBasePath(),
        buildTarget.getShortName() + ".classes.txt");
  }

  @Override
  public Iterable<String> getInputsToCompareToOutput() {
    // The deps of this rule already capture all of the inputs that should affect the cache key.
    return ImmutableSortedSet.of();
  }

  @Override
  public String getPathToOutputFile() {
    return pathToOutputFile.toString();
  }

  public JavaLibraryRule getJavaLibraryRule() {
    return javaLibraryRule;
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new RmStep(getPathToOutputFile(), /* shouldForceDeletion */ true));

    // Make sure that the output directory exists for the output file.
    steps.add(new MkdirStep(pathToOutputFile.getParent()));

    steps.add(new AccumulateClassNamesStep(
        Paths.get(javaLibraryRule.getPathToOutputFile()),
        Paths.get(getPathToOutputFile())));

    return steps.build();
  }

  public static Builder newAccumulateClassNamesBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildable.Builder {

    private JavaLibraryRule javaLibraryRule;

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    protected BuildRuleType getType() {
      return BuildRuleType._CLASS_NAMES;
    }

    @Override
    protected AccumulateClassNames newBuildable(BuildRuleParams params,
        BuildRuleResolver resolver) {
      return new AccumulateClassNames(params.getBuildTarget(), javaLibraryRule);
    }

    public Builder setJavaLibraryToDex(JavaLibraryRule javaLibraryRule) {
      this.javaLibraryRule = javaLibraryRule;
      this.addDep(javaLibraryRule.getBuildTarget());
      return this;
    }

  }
}
