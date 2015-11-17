/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.shell;

import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Test;

public class ShTestTest extends EasyMockSupport {

  @After
  public void tearDown() {
    // I don't understand why EasyMockSupport doesn't do this by default.
    verifyAll();
  }

  @Test
  public void testHasTestResultFiles() {
    ProjectFilesystem filesystem = createMock(ProjectFilesystem.class);

    ShTest shTest = new ShTest(
        new FakeBuildRuleParamsBuilder("//test/com/example:my_sh_test")
            .setProjectFilesystem(filesystem)
            .build(),
        new SourcePathResolver(new BuildRuleResolver()),
        new FakeSourcePath("run_test.sh"),
        /* args */ ImmutableList.<Arg>of(),
        /* labels */ ImmutableSet.<Label>of());

    EasyMock.expect(filesystem.isFile(shTest.getPathToTestOutputResult())).andReturn(true);
    ExecutionContext executionContext = createMock(ExecutionContext.class);

    replayAll();

    assertTrue(
        "hasTestResultFiles() should return true if result.json exists.",
        shTest.hasTestResultFiles(executionContext));
  }

  @Test
  public void depsAreRuntimeDeps() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    BuildRule extraDep = new FakeBuildRule("//:extra_dep", pathResolver);
    BuildRule dep = new FakeBuildRule("//:dep", pathResolver);

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    ShTest shTest = new ShTest(
        new FakeBuildRuleParamsBuilder(target)
            .setDeclaredDeps(ImmutableSortedSet.of(dep))
            .setExtraDeps(ImmutableSortedSet.of(extraDep))
            .build(),
        new SourcePathResolver(new BuildRuleResolver()),
        new FakeSourcePath("run_test.sh"),
        /* args */ ImmutableList.<Arg>of(),
        /* labels */ ImmutableSet.<Label>of());

    assertThat(
        shTest.getRuntimeDeps(),
        Matchers.containsInAnyOrder(dep, extraDep));
  }

}
