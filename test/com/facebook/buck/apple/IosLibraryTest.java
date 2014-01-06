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

import static org.junit.Assert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.facebook.buck.rules.FileSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.rules.coercer.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Paths;

public class IosLibraryTest {

  private IosLibraryDescription description = new IosLibraryDescription();

  @Test
  public void getInputsToCompareToOutput() {
    IosLibraryDescription.Arg arg = description.createUnpopulatedConstructorArg();
    arg.srcs = ImmutableList.of(
        Either.<SourcePath, Pair<SourcePath, String>>ofLeft(new FileSourcePath("some_source")));
    arg.headers = ImmutableSortedSet.<SourcePath>of(new FileSourcePath("some_header"));
    arg.configs = ImmutableMap.of();
    arg.frameworks = ImmutableSortedSet.of();

    FakeBuildRuleParams buildRuleParams = new FakeBuildRuleParams(new BuildTarget("//foo", "foo"));
    IosLibrary buildable = (IosLibrary) description.createBuildable(buildRuleParams, arg);

    assertThat(buildable.getInputsToCompareToOutput(), containsInAnyOrder(
        Paths.get("some_header"),
        Paths.get("some_source")));
  }
}
