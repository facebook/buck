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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PythonTestDescriptionTest {

  private static final Path PEX_PATH = Paths.get("pex");
  private static final Path TEST_MAIN = Paths.get("main");

  @Test
  public void thatTestModulesAreInComponents() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:bin"))
            .build();
    PythonTestDescription desc = new PythonTestDescription(PEX_PATH, TEST_MAIN);
    PythonTestDescription.Arg arg = desc.createUnpopulatedConstructorArg();
    arg.deps = Optional.of(ImmutableSortedSet.<BuildRule>of());
    arg.srcs = Optional.of(ImmutableSortedSet.<SourcePath>of(new TestSourcePath("blah.py")));
    arg.resources = Optional.absent();
    arg.contacts = Optional.absent();
    arg.labels = Optional.absent();
    arg.sourceUnderTest = Optional.absent();
    PythonTest testRule = desc.createBuildRule(params, resolver, arg);

    PythonBinary binRule = (PythonBinary) resolver.get(
        desc.getBinaryBuildTarget(testRule.getBuildTarget()));
    assertNotNull(binRule);

    PythonPackageComponents components = binRule.getComponents();
    assertTrue(components.getModules().containsKey(desc.getTestModulesListName()));
    assertTrue(components.getModules().containsKey(desc.getTestMainName()));
    assertEquals(binRule.getMain(), desc.getTestMainName());
  }

}
