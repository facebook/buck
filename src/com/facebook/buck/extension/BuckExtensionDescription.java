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

package com.facebook.buck.extension;


import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImmutableBuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

public class BuckExtensionDescription implements Description<BuckExtensionDescription.Arg> {

  public static final BuildRuleType TYPE = ImmutableBuildRuleType.of("buck_extension");
  private static final String BUCK_TARGET_VERSION = "7";

  private final JavacOptions javacOptions;

  public BuckExtensionDescription(JavacOptions defaultOptions) {
    this.javacOptions = JavacOptions.builder(defaultOptions)
        .setTargetLevel(BUCK_TARGET_VERSION)
        .setSourceLevel(BUCK_TARGET_VERSION)
        .build();
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
  public <A extends Arg> BuckExtension createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    return new BuckExtension(
        params,
        javacOptions,
        new SourcePathResolver(resolver),
        args.srcs,
        args.resources.get());
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public ImmutableSortedSet<SourcePath> srcs;
    public Optional<ImmutableSortedSet<SourcePath>> resources;

    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }
}
