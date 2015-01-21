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

package com.facebook.buck.extension;


import static com.facebook.buck.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.java.JarDirectoryStep;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

public class BuckExtensionTest {

  @Test
  public void finalStepShouldBeJarringUpExtension() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance("//example:extension");
    BuckExtension buildable = new BuckExtension(
        new FakeBuildRuleParamsBuilder(target).build(),
        DEFAULT_JAVAC_OPTIONS,
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSortedSet.of(new TestSourcePath("ExampleExtension.java")),
        ImmutableSortedSet.<SourcePath>of());

    BuildContext buildContext = FakeBuildContext.NOOP_CONTEXT;
    FakeBuildableContext buildableContext = new FakeBuildableContext();

    List<Step> steps = buildable.getBuildSteps(buildContext, buildableContext);

    // Compiling and copying resources must occur before jarring.
    assertTrue(Iterables.getLast(steps) instanceof JarDirectoryStep);
  }
}
