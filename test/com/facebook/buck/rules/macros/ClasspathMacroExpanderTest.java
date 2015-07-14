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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.MorePathsForTests;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.shell.ExportFileBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ClasspathMacroExpanderTest {

  private static final Path ROOT =
      MorePathsForTests.rootRelativePath(".").normalize().resolve("opt");
  private ClasspathMacroExpander expander;
  private FakeProjectFilesystem filesystem;

  @Before
  public void createMacroExpander() {
    this.expander = new ClasspathMacroExpander();
    this.filesystem = new FakeProjectFilesystem() {
      @Override
      public Path resolve(Path path) {
        return ROOT.resolve(path);
      }
    };
  }

  @Test
  public void shouldIncludeARuleIfNothingIsGiven() throws MacroException {
    final BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    BuildRule rule =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//cheese:cake"))
            .addSrc(Paths.get("Example.java"))  // Force a jar to be created
            .build(buildRuleResolver);

    assertExpandsTo(rule, buildRuleResolver, ROOT + File.separator + rule.getPathToOutput());
  }

  @Test
  public void shouldIncludeTransitiveDependencies() throws MacroException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    BuildRule dep =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:dep"))
            .addSrc(Paths.get("Dep.java"))
            .build(ruleResolver);

    BuildRule rule =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:target"))
            .addSrc(Paths.get("Other.java"))
            .addDep(dep.getBuildTarget())
            .build(ruleResolver);

    // Alphabetical sorting expected, so "dep" should be before "rule"
    assertExpandsTo(
        rule,
        ruleResolver,
        String.format(
            "%s/%s:%s/%s",
            ROOT,
            dep.getPathToOutput(),
            ROOT,
            rule.getPathToOutput()).replace(':', File.pathSeparatorChar));
  }

  @Test(expected = MacroException.class)
  public void shouldThrowAnExceptionWhenRuleToExpandDoesNotHaveAClasspath() throws MacroException {
    BuildRule rule =
        ExportFileBuilder.newExportFileBuilder(BuildTargetFactory.newInstance("//cheese:peas"))
          .setSrc(new TestSourcePath("some-file.jar"))
          .build(new BuildRuleResolver());

    expander.expand(filesystem, rule);
  }

  @Test
  public void shouldExpandTransitiveDependencies() throws MacroException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    BuildRule dep =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:dep"))
            .addSrc(Paths.get("Dep.java"))
            .build(ruleResolver);
    BuildRule rule =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:target"))
            .addSrc(Paths.get("Other.java"))
            .addDep(dep.getBuildTarget())
            .build(ruleResolver);

    BuildTarget forTarget = BuildTargetFactory.newInstance("//:rule");
    ImmutableList<BuildRule> deps =
        expander.extractAdditionalBuildTimeDeps(
            forTarget,
            ruleResolver,
            rule.getBuildTarget().toString());

    assertThat(deps, Matchers.containsInAnyOrder(rule, dep));
  }

  private void assertExpandsTo(
      BuildRule rule,
      BuildRuleResolver buildRuleResolver,
      String expectedClasspath) throws MacroException {
    String classpath = expander.expand(filesystem, rule);
    String fileClasspath = expander.expandForFile(
        rule.getBuildTarget(),
        buildRuleResolver,
        filesystem,
        ':' + rule.getBuildTarget().getShortName());

    assertEquals(expectedClasspath, classpath);
    assertEquals(String.format("'%s'", expectedClasspath), fileClasspath);
  }
}
