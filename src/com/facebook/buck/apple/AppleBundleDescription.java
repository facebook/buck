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

package com.facebook.buck.apple;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.AppleBundleDestination;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

public class AppleBundleDescription implements Description<AppleBundleDescription.Arg> {
  public static final BuildRuleType TYPE = BuildRuleType.of("apple_bundle");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> AppleBundle createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    return new AppleBundle(
        params,
        new SourcePathResolver(resolver),
        args.extension,
        args.infoPlist,
        resolver.getRule(args.binary));
  }

  @SuppressFieldNotInitialized
  public static class Arg implements HasAppleBundleFields, HasTests {
    public Either<AppleBundleExtension, String> extension;
    public BuildTarget binary;
    public Optional<SourcePath> infoPlist;
    public Optional<ImmutableMap<String, SourcePath>> headers;
    public Optional<ImmutableMap<AppleBundleDestination, SourcePath>> files;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
    @Hint(isDep = false) public Optional<ImmutableSortedSet<BuildTarget>> tests;
    public Optional<String> xcodeProductType;

    @Override
    public Either<AppleBundleExtension, String> getExtension() {
      return extension;
    }

    @Override
    public Optional<SourcePath> getInfoPlist() {
      return infoPlist;
    }

    @Override
    public ImmutableSortedSet<BuildTarget> getTests() {
      return tests.get();
    }

    @Override
    public Optional<String> getXcodeProductType() {
      return xcodeProductType;
    }
  }
}
