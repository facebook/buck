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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cxx.Archives;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.coercer.AppleSource;
import com.facebook.buck.rules.coercer.Either;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Paths;

public class AppleLibraryTest {

  private AppleLibraryDescription description =
      new AppleLibraryDescription(Archives.DEFAULT_ARCHIVE_PATH);

  @Test
  public void getInputsToCompareToOutput() {
    AppleNativeTargetDescriptionArg arg = description.createUnpopulatedConstructorArg();
    arg.srcs = Optional.of(
        ImmutableList.of(
            AppleSource.ofSourcePath(new TestSourcePath("some_source.m")),
            AppleSource.ofSourcePath(new TestSourcePath("some_header.h"))));
    arg.configs = Optional.of(
        ImmutableMap.<String, ImmutableList<Either<SourcePath, ImmutableMap<String, String>>>>of());
    arg.frameworks = Optional.of(ImmutableSortedSet.<String>of());
    arg.weakFrameworks = Optional.of(ImmutableSortedSet.<String>of());
    arg.deps = Optional.absent();
    arg.gid = Optional.absent();
    arg.headerPathPrefix = Optional.absent();
    arg.useBuckHeaderMaps = Optional.absent();

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "foo").build()).build();
    AppleLibrary buildable = description.createBuildRule(params, new BuildRuleResolver(), arg);

    assertThat(buildable.getInputsToCompareToOutput(), containsInAnyOrder(
        Paths.get("some_header.h"),
        Paths.get("some_source.m")));
  }

  @Test
  public void getDynamicFlavorOutputName() {
    AppleNativeTargetDescriptionArg arg = description.createUnpopulatedConstructorArg();
    arg.srcs = Optional.of(ImmutableList.<AppleSource>of());
    arg.configs = Optional.of(
        ImmutableMap.<String, ImmutableList<Either<SourcePath, ImmutableMap<String, String>>>>of());
    arg.frameworks = Optional.of(ImmutableSortedSet.<String>of());
    arg.weakFrameworks = Optional.of(ImmutableSortedSet.<String>of());
    arg.deps = Optional.absent();
    arg.gid = Optional.absent();
    arg.headerPathPrefix = Optional.absent();
    arg.useBuckHeaderMaps = Optional.absent();

    BuildTarget target = BuildTarget.builder("//foo", "foo")
        .setFlavor(AppleLibraryDescription.DYNAMIC_LIBRARY)
        .build();
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    AppleLibrary buildable = description.createBuildRule(params, new BuildRuleResolver(), arg);

    assertEquals(Paths.get("buck-out/bin/foo/#dynamic/foo.dylib"), buildable.getPathToOutputFile());
  }
}
