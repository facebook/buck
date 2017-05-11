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

package com.facebook.buck.rules.macros;

import static com.facebook.buck.rules.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.MorePathsForTests;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.shell.ExportFileBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class ClasspathMacroExpanderTest {

  private static final Path ROOT =
      MorePathsForTests.rootRelativePath(".").normalize().resolve("opt");
  private ClasspathMacroExpander expander;
  private FakeProjectFilesystem filesystem;

  @Before
  public void createMacroExpander() {
    this.expander = new ClasspathMacroExpander();
    this.filesystem =
        new FakeProjectFilesystem() {
          @Override
          public Path resolve(Path path) {
            return ROOT.resolve(path);
          }
        };
  }

  @Test
  public void shouldIncludeARuleIfNothingIsGiven() throws Exception {
    final BuildRuleResolver buildRuleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(buildRuleResolver));
    BuildRule rule =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//cheese:cake"))
            .addSrc(Paths.get("Example.java")) // Force a jar to be created
            .build(buildRuleResolver, filesystem);

    assertExpandsTo(
        rule,
        buildRuleResolver,
        ROOT + File.separator + pathResolver.getRelativePath(rule.getSourcePathToOutput()));
  }

  @Test
  public void shouldIncludeTransitiveDependencies() throws Exception {
    TargetNode<?, ?> depNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//exciting:dep"), filesystem)
            .addSrc(Paths.get("Dep.java"))
            .build();

    TargetNode<?, ?> ruleNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//exciting:target"), filesystem)
            .addSrc(Paths.get("Other.java"))
            .addDep(depNode.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(depNode, ruleNode);
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(ruleResolver));

    BuildRule rule = ruleResolver.requireRule(ruleNode.getBuildTarget());
    BuildRule dep = ruleResolver.requireRule(depNode.getBuildTarget());

    // Alphabetical sorting expected, so "dep" should be before "rule"
    assertExpandsTo(
        rule,
        ruleResolver,
        String.format(
            "%s" + File.separator + "%s" + File.pathSeparatorChar + "%s" + File.separator + "%s",
            ROOT,
            pathResolver.getRelativePath(dep.getSourcePathToOutput()),
            ROOT,
            pathResolver.getRelativePath(rule.getSourcePathToOutput())));
  }

  @Test(expected = MacroException.class)
  public void shouldThrowAnExceptionWhenRuleToExpandDoesNotHaveAClasspath() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(ruleResolver));
    BuildRule rule =
        ExportFileBuilder.newExportFileBuilder(BuildTargetFactory.newInstance("//cheese:peas"))
            .setSrc(new FakeSourcePath("some-file.jar"))
            .build(ruleResolver);

    expander.expand(pathResolver, rule);
  }

  @Test
  public void shouldExpandTransitiveDependencies() throws Exception {
    TargetNode<?, ?> depNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:dep"))
            .addSrc(Paths.get("Dep.java"))
            .build();
    TargetNode<?, ?> ruleNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:target"))
            .addSrc(Paths.get("Other.java"))
            .addDep(depNode.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(depNode, ruleNode);
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    BuildRule dep = ruleResolver.requireRule(depNode.getBuildTarget());
    BuildRule rule = ruleResolver.requireRule(ruleNode.getBuildTarget());

    BuildTarget forTarget = BuildTargetFactory.newInstance("//:rule");
    ImmutableList<BuildRule> deps =
        expander.extractBuildTimeDeps(
            forTarget,
            createCellRoots(filesystem),
            ruleResolver,
            ImmutableList.of(rule.getBuildTarget().toString()));

    assertThat(deps, Matchers.containsInAnyOrder(rule, dep));
  }

  @Test
  public void extractRuleKeyAppendables() throws Exception {
    TargetNode<?, ?> depNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:dep"))
            .addSrc(Paths.get("Dep.java"))
            .build();
    TargetNode<?, ?> ruleNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:target"))
            .addSrc(Paths.get("Other.java"))
            .addDep(depNode.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(depNode, ruleNode);
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    BuildRule rule = ruleResolver.requireRule(ruleNode.getBuildTarget());
    BuildRule dep = ruleResolver.requireRule(depNode.getBuildTarget());

    BuildTarget forTarget = BuildTargetFactory.newInstance("//:rule");
    Object ruleKeyAppendables =
        expander.extractRuleKeyAppendables(
            forTarget,
            createCellRoots(filesystem),
            ruleResolver,
            ImmutableList.of(rule.getBuildTarget().toString()));

    assertThat(ruleKeyAppendables, Matchers.instanceOf(ImmutableSortedSet.class));
    Set<BuildTarget> seenBuildTargets = new LinkedHashSet<>();
    for (Object appendable : ((ImmutableSortedSet<?>) ruleKeyAppendables)) {
      assertThat(appendable, Matchers.instanceOf(BuildTargetSourcePath.class));
      seenBuildTargets.add(((BuildTargetSourcePath) appendable).getTarget());
    }
    assertThat(
        seenBuildTargets,
        Matchers.equalTo(ImmutableSortedSet.of(rule.getBuildTarget(), dep.getBuildTarget())));
  }

  private void assertExpandsTo(
      BuildRule rule, BuildRuleResolver buildRuleResolver, String expectedClasspath)
      throws MacroException {
    String classpath =
        expander.expand(new SourcePathResolver(new SourcePathRuleFinder(buildRuleResolver)), rule);
    String fileClasspath =
        expander.expandForFile(
            rule.getBuildTarget(),
            createCellRoots(filesystem),
            buildRuleResolver,
            ImmutableList.of(':' + rule.getBuildTarget().getShortName()));

    assertEquals(expectedClasspath, classpath);
    assertEquals(String.format("'%s'", expectedClasspath), fileClasspath);
  }
}
