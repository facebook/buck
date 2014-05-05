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
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;

public class AndroidResourceDescription implements Description<AndroidResourceDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("android_resource");
  private final Optional<Path> aaptOverride;

  public AndroidResourceDescription(Optional<Path> aaptOverride) {
    this.aaptOverride = Preconditions.checkNotNull(aaptOverride);
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
  public Buildable createBuildable(
      BuildRuleParams params, Arg args) {
    ProjectFilesystem filesystem = params.getProjectFilesystem();

    return new AndroidResource(
        params.getBuildTarget(),
        args.deps.get(),
        args.res.orNull(),
        collectInputFiles(filesystem, args.res),
        args.rDotJavaPackage.orNull(),
        args.assets.orNull(),
        collectInputFiles(filesystem, args.assets),
        args.manifest.orNull(),
        args.hasWhitelistedStrings.or(false),
        aaptOverride);
  }

  private ImmutableSortedSet<Path> collectInputFiles(
      ProjectFilesystem filesystem,
      Optional<Path> inputDir) {
    if (!inputDir.isPresent()) {
      return ImmutableSortedSet.of();
    }
    ImmutableSortedSet.Builder<Path> paths = ImmutableSortedSet.naturalOrder();
    try {
      paths.addAll(filesystem.getFilesUnderPath(inputDir.get()));
    } catch (IOException e) {
      throw new HumanReadableException(e, "Error traversing directory: %s.", inputDir.get());
    }
    return paths.build();
  }

  public static class Arg implements ConstructorArg {
    public Optional<Path> res;
    public Optional<Path> assets;
    public Optional<Boolean> hasWhitelistedStrings;
    @Hint(name = "package")
    public Optional<String> rDotJavaPackage;
    public Optional<Path> manifest;

    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }
}
