/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.TargetGraphFactory;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class JavaLibraryDescriptionTest extends AbiCompilationModeTest {

  private BuildRule exportingRule;
  private BuildRuleResolver resolver;
  private BuildRule exportedRule;
  private JavaBuckConfig javaBuckConfig;

  @Before
  public void setUp() throws NoSuchBuildTargetException {
    javaBuckConfig = getJavaBuckConfigWithCompilationMode();

    TargetNode<?, ?> exportedNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:exported_rule"), javaBuckConfig)
            .addSrc(Paths.get("java/src/com/exported_rule/foo.java"))
            .build();
    TargetNode<?, ?> exportingNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:exporting_rule"), javaBuckConfig)
            .addSrc(Paths.get("java/src/com/exporting_rule/bar.java"))
            .addExportedDep(exportedNode.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(exportedNode, exportingNode);

    resolver = new TestBuildRuleResolver(targetGraph);

    exportedRule = resolver.requireRule(exportedNode.getBuildTarget());
    exportingRule = resolver.requireRule(exportingNode.getBuildTarget());
  }

  @Test
  public void rulesExportedFromDepsBecomeFirstOrderDeps() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRule javaLibrary =
        JavaLibraryBuilder.createBuilder(target, javaBuckConfig)
            .addDep(exportingRule.getBuildTarget())
            .build(resolver);

    // First order deps should become CalculateAbi rules if we're compiling against ABIs
    if (compileAgainstAbis.equals(TRUE)) {
      exportedRule = resolver.getRule(((JavaLibrary) exportedRule).getAbiJar().get());
    }

    assertThat(javaLibrary.getBuildDeps(), Matchers.hasItem(exportedRule));
  }

  @Test
  public void rulesExportedFromProvidedDepsBecomeFirstOrderDeps() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRule javaLibrary =
        JavaLibraryBuilder.createBuilder(target, javaBuckConfig)
            .addProvidedDep(exportingRule.getBuildTarget())
            .build(resolver);

    // First order deps should become CalculateAbi rules if we're compiling against ABIs
    if (compileAgainstAbis.equals(TRUE)) {
      exportedRule = resolver.getRule(((JavaLibrary) exportedRule).getAbiJar().get());
    }

    assertThat(javaLibrary.getBuildDeps(), Matchers.hasItem(exportedRule));
  }
}
