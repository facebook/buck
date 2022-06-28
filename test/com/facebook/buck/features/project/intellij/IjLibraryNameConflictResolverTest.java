/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.project.intellij;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.ConfigurationForConfigurationTargets;
import com.facebook.buck.core.model.RuleBasedTargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.DefaultJavaPackageFinder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.PrebuiltJarBuilder;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Paths;
import org.junit.Test;

public class IjLibraryNameConflictResolverTest {

  FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

  @Test
  public void testResolveOnEmptyGraph() {
    ImmutableSet<TargetNode<?>> targetNodes = ImmutableSet.of();

    IjLibraryNameConflictResolver resolver =
        new IjLibraryNameConflictResolver(getDataPreparer(targetNodes));

    assertThrows(IllegalArgumentException.class, () -> resolver.resolve("//does/not:exist"));
  }

  @Test
  public void testResolveOnNoConflictsGraph() {
    ImmutableSet<TargetNode<?>> targetNodes = prepareTargetNodesForNoConflictsGraph();

    IjLibraryNameConflictResolver resolver =
        new IjLibraryNameConflictResolver(getDataPreparer(targetNodes));

    assertEquals("__modules_foo_bar_baz1", resolver.resolve("//modules/foo/bar:baz1"));
    assertEquals("__modules_foo_bar_bax", resolver.resolve("//modules/foo/bar:bax"));
    assertEquals("__modules_foo_bar_baz2", resolver.resolve("//modules/foo_bar:baz2"));
    assertEquals("__modules_foo_bar_bay", resolver.resolve("//modules/foo_bar:bay"));
  }

  @Test
  public void testResolveOnConflictedGraph2() {
    ImmutableSet<TargetNode<?>> targetNodes = prepareTargetNodesForConflictedGraph2();

    IjLibraryNameConflictResolver resolver =
        new IjLibraryNameConflictResolver(getDataPreparer(targetNodes));

    assertEquals("__modules_foo_bar_baz", resolver.resolve("//modules/foo/bar:baz"));
    assertEquals("__modules_foo_bar_baz2", resolver.resolve("//modules/foo_bar:baz"));
  }

  @Test
  public void testResolveOnConflictedGraph3() {
    ImmutableSet<TargetNode<?>> targetNodes = prepareTargetNodesForConflictedGraph3();

    IjLibraryNameConflictResolver resolver =
        new IjLibraryNameConflictResolver(getDataPreparer(targetNodes));

    assertEquals(
        "__modules_foo_bar_baz__config__xplat_fbs_",
        resolver.resolve("//modules/foo.bar:baz (config//xplat:fbs)"));

    assertEquals(
        "__modules_foo_bar_baz__config__xplat_fbs_2",
        resolver.resolve("//modules/foo/bar:baz (config//xplat:fbs)"));

    assertEquals(
        "__modules_foo_bar_baz__config__xplat_fbs_3",
        resolver.resolve("//modules/foo_bar:baz (config//xplat:fbs)"));
  }

  private IjProjectTemplateDataPreparer getDataPreparer(ImmutableSet<TargetNode<?>> targetNodes) {

    IjModuleGraph moduleGraph = IjModuleGraphTest.createModuleGraph(targetNodes);

    JavaPackageFinder javaPackageFinder =
        DefaultJavaPackageFinder.createDefaultJavaPackageFinder(filesystem, ImmutableSet.of());

    return new IjProjectTemplateDataPreparer(
        javaPackageFinder,
        moduleGraph,
        filesystem,
        IjTestProjectConfig.createBuilder(FakeBuckConfig.builder().build()).build());
  }

  private ImmutableSet<TargetNode<?>> prepareTargetNodesForNoConflictsGraph() {
    TargetNode<?> preBuiltJarNode1 =
        PrebuiltJarBuilder.createBuilder(BuildTargetFactory.newInstance("//modules/foo/bar:baz1"))
            .setBinaryJar(Paths.get("modules/foo/bar/baz1.jar"))
            .build();

    TargetNode<?> preBuiltJarNode2 =
        PrebuiltJarBuilder.createBuilder(BuildTargetFactory.newInstance("//modules/foo/bar:bax"))
            .setBinaryJar(Paths.get("modules/foo/bar/bax.jar"))
            .build();

    TargetNode<?> preBuiltJarNode3 =
        PrebuiltJarBuilder.createBuilder(BuildTargetFactory.newInstance("//modules/foo_bar:baz2"))
            .setBinaryJar(Paths.get("modules/foo_bar/baz2.jar"))
            .build();

    TargetNode<?> preBuiltJarNode4 =
        PrebuiltJarBuilder.createBuilder(BuildTargetFactory.newInstance("//modules/foo_bar:bay"))
            .setBinaryJar(Paths.get("modules/foo_bar/bay.jar"))
            .build();

    TargetNode<?> mainTargetNode1 =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//modules/foo/bar:bar"))
            .addDep(preBuiltJarNode1.getBuildTarget())
            .addDep(preBuiltJarNode2.getBuildTarget())
            .addSrc(Paths.get("bar/LibNameGenTest.java"))
            .build();

    TargetNode<?> mainTargetNode2 =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//modules/foo_bar:foo_bar"))
            .addDep(preBuiltJarNode3.getBuildTarget())
            .addDep(preBuiltJarNode4.getBuildTarget())
            .addSrc(Paths.get("foo_bar/LibNameGenTest.java"))
            .build();

    return ImmutableSet.of(
        mainTargetNode1,
        mainTargetNode2,
        preBuiltJarNode1,
        preBuiltJarNode2,
        preBuiltJarNode3,
        preBuiltJarNode4);
  }

  private ImmutableSet<TargetNode<?>> prepareTargetNodesForConflictedGraph2() {

    TargetNode<?> preBuiltJarNode1 =
        PrebuiltJarBuilder.createBuilder(BuildTargetFactory.newInstance("//modules/foo/bar:baz"))
            .setBinaryJar(Paths.get("modules/foo/bar/baz.jar"))
            .build();

    TargetNode<?> preBuiltJarNode2 =
        PrebuiltJarBuilder.createBuilder(BuildTargetFactory.newInstance("//modules/foo_bar:baz"))
            .setBinaryJar(Paths.get("modules/foo_bar/baz.jar"))
            .build();

    TargetNode<?> mainTargetNode1 =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//modules/foo/bar:bar"))
            .addDep(preBuiltJarNode1.getBuildTarget())
            .addSrc(Paths.get("bar/LibNameGenTest.java"))
            .build();

    TargetNode<?> mainTargetNode2 =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//modules/foo_bar:foo_bar"))
            .addDep(preBuiltJarNode2.getBuildTarget())
            .addSrc(Paths.get("foo_bar/LibNameGenTest.java"))
            .build();

    return ImmutableSet.of(mainTargetNode1, mainTargetNode2, preBuiltJarNode1, preBuiltJarNode2);
  }

  private ImmutableSet<TargetNode<?>> prepareTargetNodesForConflictedGraph3() {

    RuleBasedTargetConfiguration ruleBasedTargetConfiguration =
        RuleBasedTargetConfiguration.of(
            BuildTargetFactory.newInstance(
                "config//xplat:fbs", ConfigurationForConfigurationTargets.INSTANCE));

    TargetNode<?> preBuiltJarNode1 =
        PrebuiltJarBuilder.createBuilder(
                BuildTargetFactory.newInstance(
                    "//modules/foo/bar:baz", ruleBasedTargetConfiguration))
            .setBinaryJar(Paths.get("modules/foo/bar/baz.jar"))
            .build();

    TargetNode<?> preBuiltJarNode2 =
        PrebuiltJarBuilder.createBuilder(
                BuildTargetFactory.newInstance(
                    "//modules/foo_bar:baz", ruleBasedTargetConfiguration))
            .setBinaryJar(Paths.get("modules/foo_bar/baz.jar"))
            .build();

    TargetNode<?> preBuiltJarNode3 =
        PrebuiltJarBuilder.createBuilder(
                BuildTargetFactory.newInstance(
                    "//modules/foo.bar:baz", ruleBasedTargetConfiguration))
            .setBinaryJar(Paths.get("modules/foo.bar/baz.jar"))
            .build();

    TargetNode<?> mainTargetNode1 =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//modules/foo/bar:bar"))
            .addDep(preBuiltJarNode1.getBuildTarget())
            .addDep(preBuiltJarNode2.getBuildTarget())
            .addDep(preBuiltJarNode3.getBuildTarget())
            .addSrc(Paths.get("bar/LibNameGenTest.java"))
            .build();

    return ImmutableSet.of(mainTargetNode1, preBuiltJarNode1, preBuiltJarNode2, preBuiltJarNode3);
  }
}
