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

package com.facebook.buck.java;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Label;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

public class JavaTestDescription implements Description<JavaTestDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("java_test");
  private final JavaCompilerEnvironment javacEnv;

  public JavaTestDescription(JavaCompilerEnvironment javacEnv) {
    this.javacEnv = Preconditions.checkNotNull(javacEnv);
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public JavaTest createBuildable(BuildRuleParams params, Arg args) {
    JavacOptions.Builder javacOptions = JavaLibraryDescription.getJavacOptions(args, javacEnv);

    AnnotationProcessingParams annotationParams =
        args.buildAnnotationProcessingParams(params.getBuildTarget());
    javacOptions.setAnnotationProcessingData(annotationParams);

    return new JavaTest(
        params,
        args.srcs.get(),
        args.resources.get(),
        args.labels.get(),
        args.contacts.get(),
        args.proguardConfig,
        javacOptions.build(),
        args.vmArgs.get(),
        args.sourceUnderTest.get());
  }

  public static class Arg extends JavaLibraryDescription.Arg {
    public Optional<ImmutableSortedSet<String>> contacts;
    public Optional<ImmutableSortedSet<Label>> labels;
    public Optional<ImmutableSortedSet<BuildTarget>> sourceUnderTest;
    public Optional<ImmutableList<String>> vmArgs;
  }
}
