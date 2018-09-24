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
import static org.hamcrest.Matchers.endsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.easymock.EasyMock;
import org.junit.Test;

public class JavaSourceJarTest {

  @Test
  public void outputNameShouldIndicateThatTheOutputIsASrcJar() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//example:target");

    JavaSourceJar rule =
        new JavaSourceJar(
            buildTarget,
            new FakeProjectFilesystem(),
            TestBuildRuleParams.create(),
            ImmutableSortedSet.of(),
            Optional.empty());
    graphBuilder.addToIndex(rule);

    SourcePath output = rule.getSourcePathToOutput();

    assertNotNull(output);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    assertThat(pathResolver.getRelativePath(output).toString(), endsWith(Javac.SRC_JAR));
  }

  @Test
  public void shouldOnlyIncludePathBasedSources() {
    SourcePath fileBased = FakeSourcePath.of("some/path/File.java");
    SourcePath ruleBased =
        DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//cheese:cake"));

    JavaPackageFinder finderStub = createNiceMock(JavaPackageFinder.class);
    expect(finderStub.findJavaPackageFolder(anyObject())).andStubReturn(Paths.get("cheese"));
    expect(finderStub.findJavaPackage((Path) anyObject())).andStubReturn("cheese");

    // No need to verify. It's a stub. I don't care about the interactions.
    EasyMock.replay(finderStub);

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//example:target");
    JavaSourceJar rule =
        new JavaSourceJar(
            buildTarget,
            new FakeProjectFilesystem(),
            TestBuildRuleParams.create(),
            ImmutableSortedSet.of(fileBased, ruleBased),
            Optional.empty());

    BuildContext buildContext =
        FakeBuildContext.withSourcePathResolver(
                DefaultSourcePathResolver.from(
                    new SourcePathRuleFinder(new TestActionGraphBuilder())))
            .withJavaPackageFinder(finderStub);
    ImmutableList<Step> steps = rule.getBuildSteps(buildContext, new FakeBuildableContext());

    // There should be a CopyStep per file being copied. Count 'em.
    int copyStepsCount = FluentIterable.from(steps).filter(CopyStep.class::isInstance).size();

    assertEquals(1, copyStepsCount);
  }
}
