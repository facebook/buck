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

package com.facebook.buck.rules.macros;

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool.Builder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaBinaryRuleBuilder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.ToolArg;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ExecutableMacroExpanderTest {

  private BuildRule createSampleJavaBinaryRule(ActionGraphBuilder graphBuilder) {
    // Create a java_binary that depends on a java_library so it is possible to create a
    // java_binary rule with a classpath entry and a main class.
    BuildRule javaLibrary =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/facebook/util:util"))
            .addSrc(Paths.get("java/com/facebook/util/ManifestGenerator.java"))
            .build(graphBuilder);

    BuildTarget buildTarget =
        BuildTargetFactory.newInstance("//java/com/facebook/util:ManifestGenerator");
    return new JavaBinaryRuleBuilder(buildTarget)
        .setDeps(ImmutableSortedSet.of(javaLibrary.getBuildTarget()))
        .setMainClass("com.facebook.util.ManifestGenerator")
        .build(graphBuilder);
  }

  @Test
  public void testReplaceBinaryBuildRuleRefsInCmd() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget target =
        BuildTargetFactory.newInstance(filesystem.getRootPath(), "//cheese", "cake");
    createSampleJavaBinaryRule(graphBuilder);
    String originalCmd = "$(exe //java/com/facebook/util:ManifestGenerator) $OUT";

    // Interpolate the build target in the genrule cmd string.

    MacroHandler macroHandler =
        new MacroHandler(ImmutableMap.of("exe", new ExecutableMacroExpander()));
    String transformedString =
        macroHandler.expand(target, createCellRoots(filesystem), graphBuilder, originalCmd);

    // Verify that the correct cmd was created.
    Path expectedClasspath =
        filesystem
            .getBuckPaths()
            .getGenDir()
            .resolve("java/com/facebook/util/ManifestGenerator.jar")
            .toAbsolutePath();

    String expectedCmd = String.format("java -jar %s $OUT", expectedClasspath);
    assertEquals(expectedCmd, transformedString);
  }

  @Test
  public void testReplaceRelativeBinaryBuildRuleRefsInCmd() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildRule rule = createSampleJavaBinaryRule(graphBuilder);
    String originalCmd = "$(exe :ManifestGenerator) $OUT";

    // Interpolate the build target in the genrule cmd string.
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    MacroHandler macroHandler =
        new MacroHandler(ImmutableMap.of("exe", new ExecutableMacroExpander()));
    String transformedString =
        macroHandler.expand(
            rule.getBuildTarget(), createCellRoots(filesystem), graphBuilder, originalCmd);

    // Verify that the correct cmd was created.
    Path expectedClasspath =
        filesystem
            .getBuckPaths()
            .getGenDir()
            .resolve("java/com/facebook/util/ManifestGenerator.jar")
            .toAbsolutePath();
    String expectedCmd = String.format("java -jar %s $OUT", expectedClasspath);
    assertEquals(expectedCmd, transformedString);
  }

  @Test
  public void testDepsGenrule() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildRule rule = createSampleJavaBinaryRule(graphBuilder);

    // Interpolate the build target in the genrule cmd string.
    String originalCmd = "$(exe :ManifestGenerator) $OUT";

    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    MacroHandler macroHandler =
        new MacroHandler(ImmutableMap.of("exe", new ExecutableMacroExpander()));
    String transformedString =
        macroHandler.expand(
            rule.getBuildTarget(), createCellRoots(filesystem), graphBuilder, originalCmd);

    // Verify that the correct cmd was created.
    Path expectedClasspath =
        filesystem
            .getBuckPaths()
            .getGenDir()
            .resolve("java/com/facebook/util/ManifestGenerator.jar")
            .toAbsolutePath();
    String expectedCmd = String.format("java -jar %s $OUT", expectedClasspath);
    assertEquals(expectedCmd, transformedString);
  }

  @Test
  public void testBuildTimeDependencies() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    BuildRule dep1 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep1"))
            .setOut("arg1")
            .build(graphBuilder, filesystem);
    BuildRule dep2 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep2"))
            .setOut("arg2")
            .build(graphBuilder, filesystem);

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params = TestBuildRuleParams.create();
    CommandTool tool =
        new Builder()
            .addArg(SourcePathArg.of(dep1.getSourcePathToOutput()))
            .addArg(SourcePathArg.of(dep2.getSourcePathToOutput()))
            .build();
    graphBuilder.addToIndex(
        new NoopBinaryBuildRule(target, new FakeProjectFilesystem(), params) {
          @Override
          public Tool getExecutableCommand() {
            return tool;
          }
        });

    // Verify that the correct cmd was created.
    ExecutableMacroExpander expander = new ExecutableMacroExpander();
    CellPathResolver cellRoots = createCellRoots(filesystem);
    assertEquals(
        ToolArg.of(tool),
        expander.expandFrom(
            target,
            cellRoots,
            graphBuilder,
            expander.parse(target, cellRoots, ImmutableList.of("//:rule"))));
    Arg expanded =
        expander.expandFrom(
            target,
            cellRoots,
            graphBuilder,
            expander.parse(target, cellRoots, ImmutableList.of("//:rule")));
    assertThat(expanded, Matchers.instanceOf(ToolArg.class));
    assertEquals(tool, ((ToolArg) expanded).getTool());
  }

  @Test
  public void extractRuleKeyAppendable() throws MacroException {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleParams params = TestBuildRuleParams.create();
    Tool tool = new CommandTool.Builder().addArg("command").build();
    graphBuilder.addToIndex(
        new NoopBinaryBuildRule(target, projectFilesystem, params) {
          @Override
          public Tool getExecutableCommand() {
            return tool;
          }
        });
    ExecutableMacroExpander expander = new ExecutableMacroExpander();
    CellPathResolver cellRoots = createCellRoots(projectFilesystem);
    assertThat(
        expander.expandFrom(
            target,
            cellRoots,
            graphBuilder,
            expander.parse(target, cellRoots, ImmutableList.of("//:rule"))),
        Matchers.equalTo(ToolArg.of(tool)));
  }

  private abstract static class NoopBinaryBuildRule extends NoopBuildRuleWithDeclaredAndExtraDeps
      implements BinaryBuildRule {

    public NoopBinaryBuildRule(
        BuildTarget buildTarget, ProjectFilesystem projectFilesystem, BuildRuleParams params) {
      super(buildTarget, projectFilesystem, params);
    }
  }
}
