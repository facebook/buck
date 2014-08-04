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

package com.facebook.buck.java;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import org.easymock.EasyMock;
import org.junit.Test;

import java.nio.file.Path;

public class JavaSourceJarTest {

  @Test
  public void outputNameShouldIndicateThatTheOutputIsASrcZip() {
    JavaSourceJar rule = new JavaSourceJar(
        new FakeBuildRuleParamsBuilder("//example:target").build(),
        ImmutableSortedSet.<SourcePath>of());

    Path output = rule.getPathToOutputFile();

    assertTrue(output.toString().endsWith(JavacStep.SRC_ZIP));
  }

  @Test
  public void shouldOnlyIncludePathBasedSources() {
    SourcePath fileBased = new TestSourcePath("some/path/File.java");
    SourcePath ruleBased = new BuildRuleSourcePath(
        new FakeBuildRule(JavaLibraryDescription.TYPE,
        BuildTargetFactory.newInstance("//cheese:cake")));

    JavaPackageFinder finderStub = createNiceMock(JavaPackageFinder.class);
    expect(finderStub.findJavaPackageFolderForPath((String) anyObject())).andStubReturn("cheese");
    expect(finderStub.findJavaPackageForPath((String) anyObject())).andStubReturn("cheese");

    // No need to verify. It's a stub. I don't care about the interactions.
    EasyMock.replay(finderStub);

    JavaSourceJar rule = new JavaSourceJar(
        new FakeBuildRuleParamsBuilder("//example:target").build(),
        ImmutableSortedSet.of(fileBased, ruleBased));

    assertEquals(ImmutableList.of(fileBased.resolve()), rule.getInputsToCompareToOutput());

    BuildContext buildContext = FakeBuildContext.newBuilder(new FakeProjectFilesystem())
        .setActionGraph(new ActionGraph(new MutableDirectedGraph<BuildRule>()))
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
