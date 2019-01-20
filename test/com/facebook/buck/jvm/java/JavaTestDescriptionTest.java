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

import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import java.nio.file.Paths;
import java.util.SortedSet;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class JavaTestDescriptionTest extends AbiCompilationModeTest {

  private JavaBuckConfig javaBuckConfig;

  @Before
  public void setUp() {
    javaBuckConfig = getJavaBuckConfigWithCompilationMode();
  }

  @Test
  public void rulesExportedFromDepsBecomeFirstOrderDeps() {
    TargetNode<?> exportedNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:exported_rule"), javaBuckConfig)
            .addSrc(Paths.get("java/src/com/exported_rule/foo.java"))
            .build();
    TargetNode<?> exportingNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:exporting_rule"), javaBuckConfig)
            .addSrc(Paths.get("java/src/com/exporting_rule/bar.java"))
            .addExportedDep(exportedNode.getBuildTarget())
            .build();
    TargetNode<?> javaTestNode =
        JavaTestBuilder.createBuilder(BuildTargetFactory.newInstance("//:rule"), javaBuckConfig)
            .addDep(exportingNode.getBuildTarget())
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(exportedNode, exportingNode, javaTestNode);

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    JavaTest javaTest = (JavaTest) graphBuilder.requireRule(javaTestNode.getBuildTarget());
    BuildRule exportedRule = graphBuilder.requireRule(exportedNode.getBuildTarget());

    // First order deps should become CalculateAbi rules if we're compiling against ABIs
    if (compileAgainstAbis.equals(TRUE)) {
      exportedRule = graphBuilder.getRule(((JavaLibrary) exportedRule).getAbiJar().get());
    }

    SortedSet<BuildRule> deps = javaTest.getCompiledTestsLibrary().getBuildDeps();
    assertThat(deps, Matchers.hasItem(exportedRule));
  }

  @Test
  public void rulesExportedFromProvidedDepsBecomeFirstOrderDeps() {
    TargetNode<?> exportedNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:exported_rule"), javaBuckConfig)
            .addSrc(Paths.get("java/src/com/exported_rule/foo.java"))
            .build();
    TargetNode<?> exportingNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:exporting_rule"), javaBuckConfig)
            .addSrc(Paths.get("java/src/com/exporting_rule/bar.java"))
            .addExportedDep(exportedNode.getBuildTarget())
            .build();
    TargetNode<?> javaTestNode =
        JavaTestBuilder.createBuilder(BuildTargetFactory.newInstance("//:rule"), javaBuckConfig)
            .addProvidedDep(exportingNode.getBuildTarget())
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(exportedNode, exportingNode, javaTestNode);

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    JavaTest javaTest = (JavaTest) graphBuilder.requireRule(javaTestNode.getBuildTarget());
    BuildRule exportedRule = graphBuilder.requireRule(exportedNode.getBuildTarget());

    // First order deps should become CalculateAbi rules if we're compiling against ABIs
    if (compileAgainstAbis.equals(TRUE)) {
      exportedRule = graphBuilder.getRule(((JavaLibrary) exportedRule).getAbiJar().get());
    }

    SortedSet<BuildRule> deps = javaTest.getCompiledTestsLibrary().getBuildDeps();
    assertThat(deps, Matchers.hasItem(exportedRule));
  }
}
