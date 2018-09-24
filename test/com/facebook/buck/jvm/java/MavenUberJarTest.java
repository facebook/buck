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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.AbstractPathSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.features.python.PythonLibrary;
import com.facebook.buck.features.python.PythonLibraryBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import java.util.List;
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
        JavaLibraryBuilder.createBuilder(javaTarget).addDep(pythonTarget);

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(pythonLibraryBuilder.build(), javaLibraryBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    PythonLibrary pythonLibrary = pythonLibraryBuilder.build(graphBuilder, filesystem, targetGraph);
    JavaLibrary javaLibrary = javaLibraryBuilder.build(graphBuilder, filesystem, targetGraph);

    MavenUberJar buildRule =
        MavenUberJar.create(
            javaLibrary,
            javaTarget,
            new FakeProjectFilesystem(),
            javaLibraryBuilder.createBuildRuleParams(graphBuilder),
            Optional.of("com.facebook.buck.jvm.java:java:jar:42"),
            Optional.empty());
    assertThat(buildRule.getBuildDeps(), Matchers.not(Matchers.hasItem(pythonLibrary)));
  }

  @Test
  public void testOnlyCorrectSourcesIncluded() {
    BuildTarget javaTarget = BuildTargetFactory.newInstance("//:java");

    BuildTarget depWithCoords = BuildTargetFactory.newInstance("//:java_dep_1");
    BuildTarget depWithoutCoords = BuildTargetFactory.newInstance("//:java_dep_2");

    depWithCoords.compareTo(depWithoutCoords);

    FakeJavaLibrary javaLibraryWithCoords =
        new FakeJavaLibrary(depWithCoords)
            .setMavenCoords("coord")
            .setJavaSrcs(ImmutableSortedSet.of(FakeSourcePath.of("depWithCoords")));
    FakeJavaLibrary javaLibraryWithoutCoords =
        new FakeJavaLibrary(depWithoutCoords)
            .setJavaSrcs(ImmutableSortedSet.of(FakeSourcePath.of("depWithoutCoords")));
    ImmutableSortedSet<BuildRule> deps =
        ImmutableSortedSet.of(javaLibraryWithCoords, javaLibraryWithoutCoords);

    BuildRuleParams params = TestBuildRuleParams.create().withDeclaredDeps(deps);

    MavenUberJar.SourceJar buildRule =
        MavenUberJar.SourceJar.create(
            javaTarget,
            new FakeProjectFilesystem(),
            params,
            ImmutableSortedSet.of(FakeSourcePath.of("javaTarget")),
            Optional.empty(),
            Optional.empty());

    List<BuildRule> packagedDeps = Lists.newArrayList(buildRule.getPackagedDependencies());

    assertEquals(1, packagedDeps.size());
    assertEquals(javaLibraryWithoutCoords, packagedDeps.get(0));

    ImmutableSortedSet<SourcePath> sources = buildRule.getSources();
    assertEquals(2, sources.size());
    assertEquals(
        "depWithoutCoords",
        ((AbstractPathSourcePath) sources.first()).getRelativePath().toString());
    assertEquals(
        "javaTarget", ((AbstractPathSourcePath) sources.last()).getRelativePath().toString());
  }
}
