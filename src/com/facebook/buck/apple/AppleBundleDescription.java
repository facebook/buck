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
import com.facebook.buck.rules.BuildRule;
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
import com.google.common.collect.Maps;

import java.nio.file.Path;

public class AppleBundleDescription implements Description<AppleBundleDescription.Arg> {
  public static final BuildRuleType TYPE = BuildRuleType.of("apple_bundle");

  public static final ImmutableMap<AppleBundleDestination.SubfolderSpec, String>
      IOS_APP_SUBFOLDER_SPEC_MAP = Maps.immutableEnumMap(
          ImmutableMap.<AppleBundleDestination.SubfolderSpec, String>builder()
              .put(AppleBundleDestination.SubfolderSpec.ABSOLUTE, "")
              .put(AppleBundleDestination.SubfolderSpec.WRAPPER, "")
              .put(AppleBundleDestination.SubfolderSpec.EXECUTABLES, "")
              .put(AppleBundleDestination.SubfolderSpec.RESOURCES, "")
              .put(AppleBundleDestination.SubfolderSpec.FRAMEWORKS, "Frameworks")
              .put(
                  AppleBundleDestination.SubfolderSpec.SHARED_FRAMEWORKS,
                  "SharedFrameworks")
              .put(AppleBundleDestination.SubfolderSpec.SHARED_SUPPORT, "")
              .put(AppleBundleDestination.SubfolderSpec.PLUGINS, "PlugIns")
              .put(AppleBundleDestination.SubfolderSpec.JAVA_RESOURCES, "")
              .put(AppleBundleDestination.SubfolderSpec.PRODUCTS, "")
              .build());

  // TODO(user): Add OSX_APP_SUBFOLDER_SPEC_MAP etc.

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

    // TODO(user): Sort through the changes needed to make project generation work with
    // binary being optional.
    Optional<BuildRule> binaryRule = Optional.of(resolver.getRule(args.binary));
    return new AppleBundle(
        params,
        new SourcePathResolver(resolver),
        args.extension,
        args.infoPlist,
        binaryRule,
        // TODO(user): Check the flavor and decide whether to lay out with iOS or OS X style.
        IOS_APP_SUBFOLDER_SPEC_MAP,
        args.dirs.get(),
        args.files.get());
  }

  @SuppressFieldNotInitialized
  public static class Arg implements HasAppleBundleFields, HasTests {
    public Either<AppleBundleExtension, String> extension;
    public BuildTarget binary;
    public Optional<SourcePath> infoPlist;
    public Optional<ImmutableMap<String, SourcePath>> headers;
    public Optional<ImmutableMap<Path, AppleBundleDestination>> dirs;
    public Optional<ImmutableMap<SourcePath, AppleBundleDestination>> files;
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

    @Override
    public ImmutableMap<Path, AppleBundleDestination> getDirs() {
      return dirs.get();
    }

    @Override
    public ImmutableMap<SourcePath, AppleBundleDestination> getFiles() {
      return files.get();
    }
  }
}
