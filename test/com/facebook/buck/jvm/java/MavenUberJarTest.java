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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.python.PythonLibrary;
import com.facebook.buck.python.PythonLibraryBuilder;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

import java.util.Optional;

public class MavenUberJarTest {

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    filesystem = new FakeProjectFilesystem();
  }

  @Test
  public void onlyJavaDepsIncluded() throws NoSuchBuildTargetException {
    BuildTarget pythonTarget = BuildTargetFactory.newInstance("//:python");
    BuildTarget javaTarget = BuildTargetFactory.newInstance("//:java");

    PythonLibraryBuilder pythonLibraryBuilder =
        PythonLibraryBuilder
            .createBuilder(pythonTarget);
    JavaLibraryBuilder javaLibraryBuilder =
        JavaLibraryBuilder
            .createBuilder(javaTarget)
            .addResource(new BuildTargetSourcePath(pythonTarget));

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(pythonLibraryBuilder.build(), javaLibraryBuilder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    PythonLibrary pythonLibrary =
        (PythonLibrary) pythonLibraryBuilder.build(resolver, filesystem, targetGraph);
    JavaLibrary javaLibrary =
        (JavaLibrary) javaLibraryBuilder.build(resolver, filesystem, targetGraph);

    MavenUberJar buildRule =
        MavenUberJar.create(
            javaLibrary,
            javaLibraryBuilder.createBuildRuleParams(resolver, filesystem),
            new SourcePathResolver(new SourcePathRuleFinder(resolver)),
            Optional.of("com.facebook.buck.jvm.java:java:jar:42"),
            Optional.empty());
    assertThat(buildRule.getDeps(), Matchers.not(Matchers.hasItem(pythonLibrary)));
  }
}
