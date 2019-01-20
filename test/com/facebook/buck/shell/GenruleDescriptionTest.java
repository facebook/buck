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

package com.facebook.buck.shell;

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.AllExistingProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.macros.ClasspathMacro;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.facebook.buck.sandbox.NoSandboxExecutionStrategy;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Paths;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.Test;

public class GenruleDescriptionTest {

  @Test
  public void testImplicitDepsAreAddedCorrectly() throws Exception {
    GenruleDescription genruleDescription =
        new GenruleDescription(
            new ToolchainProviderBuilder().build(),
            FakeBuckConfig.builder().build(),
            new NoSandboxExecutionStrategy());
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar");
    Map<String, Object> instance =
        ImmutableMap.of(
            "name",
            buildTarget.getShortName(),
            "srcs",
            ImmutableList.of(":baz", "//biz:baz"),
            "out",
            "AndroidManifest.xml",
            "cmd",
            "$(exe //bin:executable) $(location :arg)");
    ProjectFilesystem projectFilesystem = new AllExistingProjectFilesystem();
    ConstructorArgMarshaller marshaller =
        new DefaultConstructorArgMarshaller(new DefaultTypeCoercerFactory());
    ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();
    ImmutableSet.Builder<VisibilityPattern> visibilityPatterns = ImmutableSet.builder();
    ImmutableSet.Builder<VisibilityPattern> withinViewPatterns = ImmutableSet.builder();
    GenruleDescriptionArg constructorArg =
        marshaller.populate(
            createCellRoots(projectFilesystem),
            projectFilesystem,
            buildTarget,
            GenruleDescriptionArg.class,
            declaredDeps,
            instance);
    TargetNode<GenruleDescriptionArg> targetNode =
        new TargetNodeFactory(new DefaultTypeCoercerFactory())
            .createFromObject(
                genruleDescription,
                constructorArg,
                projectFilesystem,
                buildTarget,
                declaredDeps.build(),
                visibilityPatterns.build(),
                withinViewPatterns.build(),
                createCellRoots(projectFilesystem));
    assertEquals(
        "SourcePaths and targets from cmd string should be extracted as extra deps.",
        ImmutableSet.of("//foo:baz", "//biz:baz", "//bin:executable", "//foo:arg"),
        targetNode
            .getExtraDeps()
            .stream()
            .map(Object::toString)
            .collect(ImmutableSet.toImmutableSet()));
  }

  @Test
  public void testClasspathTransitiveDepsBecomeFirstOrderDeps() {
    TargetNode<?> transitiveDepNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:dep"))
            .addSrc(Paths.get("Dep.java"))
            .build();
    TargetNode<?> depNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:target"))
            .addSrc(Paths.get("Other.java"))
            .addDep(transitiveDepNode.getBuildTarget())
            .build();
    TargetNode<?> genruleNode =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setCmd(StringWithMacrosUtils.format("%s", ClasspathMacro.of(depNode.getBuildTarget())))
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(transitiveDepNode, depNode, genruleNode);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    BuildRule dep = graphBuilder.requireRule(depNode.getBuildTarget());
    BuildRule transitiveDep = graphBuilder.requireRule(transitiveDepNode.getBuildTarget());
    BuildRule genrule = graphBuilder.requireRule(genruleNode.getBuildTarget());

    assertThat(genrule.getBuildDeps(), Matchers.containsInAnyOrder(dep, transitiveDep));
  }
}
