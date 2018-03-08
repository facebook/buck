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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.python.PythonLibrary;
import com.facebook.buck.python.PythonLibraryBuilder;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

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

    PythonLibraryBuilder pythonLibraryBuilder = PythonLibraryBuilder.createBuilder(pythonTarget);
    JavaLibraryBuilder javaLibraryBuilder =
        JavaLibraryBuilder.createBuilder(javaTarget)
            .addResource(DefaultBuildTargetSourcePath.of(pythonTarget));

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(pythonLibraryBuilder.build(), javaLibraryBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);

    PythonLibrary pythonLibrary = pythonLibraryBuilder.build(resolver, filesystem, targetGraph);
    JavaLibrary javaLibrary = javaLibraryBuilder.build(resolver, filesystem, targetGraph);

    MavenUberJar buildRule =
        MavenUberJar.create(
            javaLibrary,
            javaTarget,
            new FakeProjectFilesystem(),
            javaLibraryBuilder.createBuildRuleParams(resolver),
            Optional.of("com.facebook.buck.jvm.java:java:jar:42"),
            Optional.empty());
    assertThat(buildRule.getBuildDeps(), Matchers.not(Matchers.hasItem(pythonLibrary)));
  }
}
