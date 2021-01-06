/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.shell;

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.nameresolver.SingleRootCellNameResolverProvider;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.impl.ThrowingTargetConfigurationTransformer;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypes;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.select.impl.ThrowingSelectableConfigurationContext;
import com.facebook.buck.core.select.impl.ThrowingSelectorListResolver;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.AllExistingProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DataTransferObjectDescriptor;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.macros.ClasspathMacro;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.facebook.buck.sandbox.NoSandboxExecutionStrategy;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class GenruleDescriptionTest {

  @Test
  public void testImplicitDepsAreAddedCorrectly() throws Exception {
    DefaultTypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();

    GenruleDescription genruleDescription =
        new GenruleDescription(
            new ToolchainProviderBuilder().build(),
            FakeBuckConfig.builder().build(),
            new NoSandboxExecutionStrategy());
    KnownNativeRuleTypes knownRuleTypes =
        KnownNativeRuleTypes.of(
            ImmutableList.of(genruleDescription), ImmutableList.of(), ImmutableList.of());

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar");
    DataTransferObjectDescriptor<GenruleDescriptionArg> builder =
        knownRuleTypes
            .getDescriptorByNameChecked("genrule", GenruleDescriptionArg.class)
            .dataTransferObjectDescriptor(typeCoercerFactory);

    Map<String, Object> instance =
        ImmutableMap.of(
            "name",
            buildTarget.getShortName(),
            "srcs",
            ImmutableList.of(":baz", "//biz:baz"),
            "out",
            Optional.of("AndroidManifest.xml"),
            "cmd",
            Optional.of("$(exe //bin:executable) $(location :arg)"));
    ProjectFilesystem projectFilesystem = new AllExistingProjectFilesystem();
    ConstructorArgMarshaller marshaller = new DefaultConstructorArgMarshaller();
    ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();
    ImmutableSet.Builder<VisibilityPattern> visibilityPatterns = ImmutableSet.builder();
    ImmutableSet.Builder<VisibilityPattern> withinViewPatterns = ImmutableSet.builder();
    GenruleDescriptionArg constructorArg =
        marshaller.populate(
            createCellRoots(projectFilesystem).getCellNameResolver(),
            projectFilesystem,
            new ThrowingSelectorListResolver(),
            new ThrowingTargetConfigurationTransformer(),
            new ThrowingSelectableConfigurationContext(),
            buildTarget,
            UnconfiguredTargetConfiguration.INSTANCE,
            DependencyStack.root(),
            builder,
            declaredDeps,
            ImmutableSet.builder(),
            instance);
    TargetNode<GenruleDescriptionArg> targetNode =
        new TargetNodeFactory(
                new DefaultTypeCoercerFactory(), SingleRootCellNameResolverProvider.INSTANCE)
            .createFromObject(
                genruleDescription,
                constructorArg,
                projectFilesystem,
                buildTarget,
                DependencyStack.root(),
                declaredDeps.build(),
                ImmutableSortedSet.of(),
                visibilityPatterns.build(),
                withinViewPatterns.build());
    assertEquals(
        "SourcePaths and targets from cmd string should be extracted as extra deps.",
        ImmutableSet.of("//foo:baz", "//biz:baz", "//bin:executable", "//foo:arg"),
        targetNode.getExtraDeps().stream()
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
            .setCmd(
                StringWithMacrosUtils.format(
                    "%s",
                    ClasspathMacro.of(
                        BuildTargetWithOutputs.of(
                            depNode.getBuildTarget(), OutputLabel.defaultLabel()))))
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(transitiveDepNode, depNode, genruleNode);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    BuildRule dep = graphBuilder.requireRule(depNode.getBuildTarget());
    BuildRule transitiveDep = graphBuilder.requireRule(transitiveDepNode.getBuildTarget());
    BuildRule genrule = graphBuilder.requireRule(genruleNode.getBuildTarget());

    assertThat(genrule.getBuildDeps(), Matchers.containsInAnyOrder(dep, transitiveDep));
  }

  /** Tests that omitting remote results in a genrule that does not execute remotely. */
  @Test
  public void testGenrulesDefaultToNotRemoteExecutable() {
    TargetNode<?> genruleNode =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setBash("echo something > out")
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(genruleNode);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    Genrule genrule = (Genrule) graphBuilder.requireRule(genruleNode.getBuildTarget());
    assertFalse(genrule.getBuildable().shouldExecuteRemotely());
  }

  /** Tests that passing "false" to remote results in a genrule that does not execute remotely. */
  @Test
  public void testGenruleRemoteExecutableFalse() {
    TargetNode<?> genruleNode =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setBash("echo something > out")
            .setRemote(false)
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(genruleNode);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    Genrule genrule = (Genrule) graphBuilder.requireRule(genruleNode.getBuildTarget());
    assertFalse(genrule.getBuildable().shouldExecuteRemotely());
  }

  /**
   * Tests that passing "true" to remote has no effect if there is no corresponding config key
   * present that enables RE for genrules.
   */
  @Test
  public void testGenrulesAreNotExecutableWithoutConfig() {
    TargetNode<?> genruleNode =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setBash("echo something > out")
            .setRemote(true)
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(genruleNode);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    Genrule genrule = (Genrule) graphBuilder.requireRule(genruleNode.getBuildTarget());
    assertFalse(genrule.getBuildable().shouldExecuteRemotely());
  }

  /**
   * Tests that passing "true" to remote results in a genrule that executes remotely if the correct
   * configuration key is present in buckconfig.
   */
  @Test
  public void testGenrulesAreExecutableWithConfig() {
    ImmutableMap<String, ImmutableMap<String, String>> config =
        ImmutableMap.<String, ImmutableMap<String, String>>builder()
            .put(
                "remoteexecution",
                ImmutableMap.<String, String>builder()
                    .put(
                        RemoteExecutionConfig.getUseRemoteExecutionForGenruleIfRequestedField(
                            "genrule"),
                        "true")
                    .build())
            .build();
    BuckConfig buckConfig = FakeBuckConfig.builder().setSections(config).build();
    TargetNode<?> genruleNode =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"), buckConfig)
            .setOut("out")
            .setBash("echo something > out")
            .setRemote(true)
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(genruleNode);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    Genrule genrule = (Genrule) graphBuilder.requireRule(genruleNode.getBuildTarget());
    assertTrue(genrule.getBuildable().shouldExecuteRemotely());
  }
}
