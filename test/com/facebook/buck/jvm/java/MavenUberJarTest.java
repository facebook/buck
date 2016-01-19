/*
 * Copyright 2016-present Facebook, Inc.
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

import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.python.PythonLibraryBuilder;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class MavenUberJarTest {

  private ProjectFilesystem filesystem;
  private BuildRuleResolver ruleResolver;

  @Before
  public void setUp() {
    filesystem = new FakeProjectFilesystem();

    ruleResolver = new BuildRuleResolver(
        TargetGraph.EMPTY,
        new BuildTargetNodeToBuildRuleTransformer());
  }


  @Test
  public void onlyJavaDepsIncluded() throws NoSuchBuildTargetException {
    BuildTarget pythonTarget = BuildTargetFactory.newInstance("//:python");
    BuildTarget javaTarget = BuildTargetFactory.newInstance("//:java");

    BuildRule pythonLibrary = PythonLibraryBuilder
        .createBuilder(pythonTarget)
        .build(ruleResolver);

    JavaLibraryBuilder javaLibraryBuilder = JavaLibraryBuilder
        .createBuilder(javaTarget)
        .addResource(new BuildTargetSourcePath(pythonTarget));
    MavenUberJar buildRule = MavenUberJar.create(
        (JavaLibrary) javaLibraryBuilder.build(ruleResolver),
        javaLibraryBuilder.createBuildRuleParams(ruleResolver, filesystem),
        new SourcePathResolver(ruleResolver),
        Optional.of("com.facebook.buck.jvm.java:java:jar:42"));
    assertThat(buildRule.getDeps(), Matchers.not(Matchers.hasItem(pythonLibrary)));
  }
}
