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

package com.facebook.buck.android;

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;
import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;
import static com.facebook.buck.rules.BuildableProperties.Kind.TEST;

import com.facebook.buck.java.AnnotationProcessingParams;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.java.JavaTestRule;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.TargetDevice;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.io.File;
import java.util.List;
import java.util.Set;

public class RobolectricTestRule extends JavaTestRule {

  private final static BuildableProperties PROPERTIES = new BuildableProperties(
      ANDROID, LIBRARY, TEST);

  /**
   * Used by robolectric test runner to get list of resource directories that
   * can be used for tests.
   */
  final static String LIST_OF_RESOURCE_DIRECTORIES_PROPERTY_NAME =
      "buck.robolectric_res_directories";

  private final static Function<HasAndroidResourceDeps, String> RESOURCE_DIRECTORY_FUNCTION =
      new Function<HasAndroidResourceDeps, String>() {
    @Override
    public String apply(HasAndroidResourceDeps input) {
      return input.getRes();
    }
  };

  protected RobolectricTestRule(BuildRuleParams buildRuleParams,
      Set<String> srcs,
      Set<SourcePath> resources,
      Optional<DummyRDotJava> optionalDummyRDotJava,
      Set<String> labels,
      Set<String> contacts,
      Optional<String> proguardConfig,
      JavacOptions javacOptions,
      List<String> vmArgs,
      ImmutableSet<JavaLibraryRule> sourceUnderTest) {
    super(buildRuleParams,
        srcs,
        resources,
        optionalDummyRDotJava,
        labels,
        contacts,
        proguardConfig,
        javacOptions,
        vmArgs,
        sourceUnderTest);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.ROBOLECTRIC_TEST;
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  @Override
  public List<String> getInputsToCompareToOutput() {
    return super.getInputsToCompareToOutput();
  }

  @Override
  protected void onAmendVmArgs(ImmutableList.Builder<String> vmArgsBuilder,
      Optional<TargetDevice> targetDevice) {
    super.onAmendVmArgs(vmArgsBuilder, targetDevice);
    Preconditions.checkState(optionalDummyRDotJava.isPresent(),
        "DummyRDotJava must have been created by the BuildRuleBuilder!");
    vmArgsBuilder.add(getRobolectricResourceDirectories(
        optionalDummyRDotJava.get().getAndroidResourceDeps()));
  }

  @VisibleForTesting
  String getRobolectricResourceDirectories(List<HasAndroidResourceDeps> resourceDeps) {
    String resourceDirectories = Joiner.on(File.pathSeparator)
        .join(Iterables.transform(resourceDeps, RESOURCE_DIRECTORY_FUNCTION));

    return String.format("-D%s=%s",
        LIST_OF_RESOURCE_DIRECTORIES_PROPERTY_NAME,
        resourceDirectories);
  }

  public static Builder newRobolectricTestRuleBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends JavaTestRule.Builder {


    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public RobolectricTestRule build(BuildRuleResolver ruleResolver) {
      ImmutableSet<JavaLibraryRule> sourceUnderTest = generateSourceUnderTest(sourcesUnderTest,
          ruleResolver);

      ImmutableList.Builder<String> allVmArgs = ImmutableList.builder();
      allVmArgs.addAll(vmArgs);

      AnnotationProcessingParams processingParams = getAnnotationProcessingBuilder().build(ruleResolver);
      javacOptions.setAnnotationProcessingData(processingParams);

      BuildRuleParams buildRuleParams = createBuildRuleParams(ruleResolver);

      JavaLibraryGraphEnhancer.Result result =
          new JavaLibraryGraphEnhancer(buildTarget, buildRuleParams, params)
              .createBuildableForAndroidResources(
                  ruleResolver, /* createBuildableIfEmptyDeps */ true);

      return new RobolectricTestRule(result.getBuildRuleParams(),
          srcs,
          resources,
          result.getOptionalDummyRDotJava(),
          labels,
          contacts,
          proguardConfig,
          javacOptions.build(),
          allVmArgs.build(),
          sourceUnderTest);
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

  }
}
