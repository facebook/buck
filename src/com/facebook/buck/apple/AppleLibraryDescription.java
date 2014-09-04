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

package com.facebook.buck.apple;

import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

public class AppleLibraryDescription implements
    Description<AppleNativeTargetDescriptionArg>, Flavored {
  public static final BuildRuleType TYPE = new BuildRuleType("apple_library");

  public static final Flavor DYNAMIC_LIBRARY = new Flavor("dynamic");

  private final Path archiver;

  public AppleLibraryDescription(Path archiver) {
    this.archiver = Preconditions.checkNotNull(archiver);
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public AppleNativeTargetDescriptionArg createUnpopulatedConstructorArg() {
    return new AppleNativeTargetDescriptionArg();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    boolean match = true;
    for (Flavor flavor : flavors) {
      match &= DYNAMIC_LIBRARY.equals(flavor) || Flavor.DEFAULT.equals(flavor);
    }
    return match;
  }

  @Override
  public <A extends AppleNativeTargetDescriptionArg> AppleLibrary createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    return new AppleLibrary(
        params,
        args,
        TargetSources.ofAppleSources(args.srcs.get()),
        archiver,
        params.getBuildTarget().getFlavors().contains(DYNAMIC_LIBRARY));
  }
}
