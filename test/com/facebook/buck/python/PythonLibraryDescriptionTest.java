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

package com.facebook.buck.python;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.coercer.SourceList;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Paths;

public class PythonLibraryDescriptionTest {

  @Test
  public void baseModule() {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    String sourceName = "main.py";
    SourcePath source = new TestSourcePath("foo/" + sourceName);
    PythonLibraryDescription desc = new PythonLibraryDescription();
    PythonLibraryDescription.Arg arg = desc.createUnpopulatedConstructorArg();
    arg.deps = Optional.absent();
    arg.resources = Optional.absent();
    arg.srcs = Optional.of(SourceList.ofUnnamedSources(ImmutableSortedSet.of(source)));

    // Run without a base module set and verify it defaults to using the build target
    // base name.
    arg.baseModule = Optional.absent();
    PythonLibrary normalRule = desc
        .createBuildRule(TargetGraph.EMPTY, params, resolver, arg);
    assertEquals(
        ImmutableMap.of(
            target.getBasePath().resolve(sourceName),
            source),
        normalRule.getSrcs());

    // Run *with* a base module set and verify it gets used to build the main module path.
    arg.baseModule = Optional.of("blah");
    PythonLibrary baseModuleRule = desc
        .createBuildRule(TargetGraph.EMPTY, params, resolver, arg);
    assertEquals(
        ImmutableMap.of(
            Paths.get(arg.baseModule.get()).resolve(sourceName),
            source),
        baseModuleRule.getSrcs());
  }

}
