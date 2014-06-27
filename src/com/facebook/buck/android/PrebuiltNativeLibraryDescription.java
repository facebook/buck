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

package com.facebook.buck.android;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;

public class PrebuiltNativeLibraryDescription
    implements Description<PrebuiltNativeLibraryDescription.Args> {

  public static final BuildRuleType TYPE = new BuildRuleType("prebuilt_native_library");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Args createUnpopulatedConstructorArg() {
    return new Args();
  }

  @Override
  public <A extends Args> PrebuiltNativeLibrary createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    ImmutableSortedSet<Path> librarySources;
    try {
      librarySources = ImmutableSortedSet.copyOf(
          params.getProjectFilesystem().getFilesUnderPath(args.nativeLibs));
    } catch (IOException e) {
      throw new HumanReadableException(e, "Error traversing directory %s.", args.nativeLibs);
    }

    return new PrebuiltNativeLibrary(
        params,
        args.nativeLibs,
        args.isAsset.or(false),
        librarySources
    );
  }

  public static class Args implements ConstructorArg {
    public Optional<Boolean> isAsset;
    public Path nativeLibs;

    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }
}
