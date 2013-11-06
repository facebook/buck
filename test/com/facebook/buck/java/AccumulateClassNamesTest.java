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

package com.facebook.buck.java;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import org.easymock.EasyMockSupport;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class AccumulateClassNamesTest extends EasyMockSupport {

  @Test
  public void testObserversForAccumulateClassNames() throws IOException {
    // Create a JavaLibraryRule with classes to accumulate.
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    JavaLibraryRule javaLibraryRule = buildRuleResolver.buildAndAddToIndex(PrebuiltJarRule
        .newPrebuiltJarRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(new BuildTarget("//foo/bar", "jar"))
        .setBinaryJar("foo/bar/example.jar"));

    // Create the Builder.
    AbstractBuildRuleBuilderParams params = new FakeAbstractBuildRuleBuilderParams();
    AccumulateClassNames.Builder builder = AccumulateClassNames.newAccumulateClassNamesBuilder(
        params);
    assertEquals(BuildRuleType._CLASS_NAMES, builder.getType());


    // Construct the Buildable.
    BuildTarget buildTarget = new BuildTarget("//foo/bar", "baz", "class_names");
    builder.setBuildTarget(buildTarget);
    builder.setJavaLibraryToDex(javaLibraryRule);
    BuildRule buildRule = buildRuleResolver.buildAndAddToIndex(builder);
    AccumulateClassNames accumulateClassNames = (AccumulateClassNames) buildRule.getBuildable();

    // Test the observers.
    String pathToOutput = "buck-out/gen/foo/bar/baz#class_names.classes.txt";
    assertEquals(pathToOutput,
        accumulateClassNames.getPathToOutputFile());
    assertEquals("There should not be any input files that factor into the cache key.",
        ImmutableSortedSet.of(),
        accumulateClassNames.getInputsToCompareToOutput());

    // Mock out objects so getBuildSteps() can be invoked.
    BuildContext buildContext = createMock(BuildContext.class);
    BuildableContext buildableContext = createMock(BuildableContext.class);

    // Create the build steps.
    replayAll();
    List<Step> steps = accumulateClassNames.getBuildSteps(buildContext, buildableContext);
    verifyAll();

    // Verify the build steps.
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(new File("."));
    ExecutionContext context = TestExecutionContext
        .newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();
    Path absolutePathToOutput = projectFilesystem.resolve(Paths.get(pathToOutput));
    MoreAsserts.assertSteps("Delete old classes.txt file, if present, and then write a new one.",
        ImmutableList.of(
          "rm -f " + absolutePathToOutput,
          "mkdir -p " + absolutePathToOutput.getParent(),
          "get_class_names foo/bar/example.jar > " + pathToOutput),
        steps,
        context);
  }
}
