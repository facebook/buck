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

package com.facebook.buck.jvm.java;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import org.easymock.EasyMock;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class JavaSourceJarTest {

  @Test
  public void outputNameShouldIndicateThatTheOutputIsASrcJar() {
    JavaSourceJar rule = new JavaSourceJar(
        new FakeBuildRuleParamsBuilder("//example:target").build(),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSortedSet.<SourcePath>of(),
        Optional.<String>absent());

    Path output = rule.getPathToOutput();

    assertNotNull(output);
    assertTrue(output.toString().endsWith(Javac.SRC_JAR));
  }

  @Test
  public void shouldOnlyIncludePathBasedSources() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    SourcePath fileBased = new TestSourcePath("some/path/File.java");
    SourcePath ruleBased = new BuildTargetSourcePath(
        BuildTargetFactory.newInstance("//cheese:cake"));

    JavaPackageFinder finderStub = createNiceMock(JavaPackageFinder.class);
    expect(finderStub.findJavaPackageFolder((Path) anyObject()))
        .andStubReturn(Paths.get("cheese"));
    expect(finderStub.findJavaPackage((Path) anyObject())).andStubReturn("cheese");

    // No need to verify. It's a stub. I don't care about the interactions.
    EasyMock.replay(finderStub);

    JavaSourceJar rule = new JavaSourceJar(
        new FakeBuildRuleParamsBuilder("//example:target").build(),
        pathResolver,
        ImmutableSortedSet.of(fileBased, ruleBased),
        Optional.<String>absent());

    BuildContext buildContext = FakeBuildContext.newBuilder()
        .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
        .setJavaPackageFinder(finderStub)
        .build();
    ImmutableList<Step> steps = rule.getBuildSteps(
        buildContext,
        new FakeBuildableContext());

    // There should be a CopyStep per file being copied. Count 'em.
    int copyStepsCount = FluentIterable.from(steps)
        .filter(Predicates.instanceOf(CopyStep.class))
        .size();

    assertEquals(1, copyStepsCount);
  }
}
