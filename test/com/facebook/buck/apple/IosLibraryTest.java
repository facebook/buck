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
import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.coercer.AppleSource;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Paths;

public class IosLibraryTest {

  private IosLibraryDescription description = new IosLibraryDescription();

  @Test
  public void getInputsToCompareToOutput() {
    AppleNativeTargetDescriptionArg arg = description.createUnpopulatedConstructorArg();
    arg.srcs = ImmutableList.of(
        AppleSource.ofSourcePath(new TestSourcePath("some_source.m")),
        AppleSource.ofSourcePath(new TestSourcePath("some_header.h")));
    arg.configs = ImmutableMap.of();
    arg.frameworks = ImmutableSortedSet.of();
    arg.deps = Optional.absent();

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "foo").build()).build();
    IosLibrary buildable = description.createBuildRule(params, new BuildRuleResolver(), arg);

    assertThat(buildable.getInputsToCompareToOutput(), containsInAnyOrder(
        Paths.get("some_header.h"),
        Paths.get("some_source.m")));
  }
}
