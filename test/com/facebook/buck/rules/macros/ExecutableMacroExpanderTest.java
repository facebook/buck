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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool.Builder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavaBinaryRuleBuilder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.ToolArg;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.shell.GenruleBuilder;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class ExecutableMacroExpanderTest {

  private ProjectFilesystem filesystem;
  private ActionGraphBuilder graphBuilder;
  private CellPathResolver cellPathResolver;
  private StringWithMacrosConverter converter;

  @Before
  public void setUp() throws Exception {
    filesystem = new FakeProjectFilesystem();
    cellPathResolver = TestCellBuilder.createCellRoots(filesystem);
    graphBuilder = new TestActionGraphBuilder();
  }

  private void createConverter(BuildTarget buildTarget) {
    converter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(buildTarget)
            .setCellPathResolver(cellPathResolver)
            .addExpanders(new ExecutableMacroExpander())
            .build();
  }

  @Test
  public void testReplaceBinaryBuildRuleRefsInCmd() throws Exception {
    BuildTarget buildTarget =
        BuildTargetFactory.newInstance("//java/com/facebook/util:ManifestGenerator");
    createConverter(buildTarget);
    new JavaBinaryRuleBuilder(buildTarget)
        .setMainClass("com.facebook.util.ManifestGenerator")
        .build(graphBuilder);

    String transformedString =
        coerceAndStringify(
            "$(exe //java/com/facebook/util:ManifestGenerator) $OUT",
            graphBuilder.requireRule(buildTarget));

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
    BuildTarget buildTarget =
        BuildTargetFactory.newInstance("//java/com/facebook/util:ManifestGenerator");
    createConverter(buildTarget);
    new JavaBinaryRuleBuilder(buildTarget)
        .setMainClass("com.facebook.util.ManifestGenerator")
        .build(graphBuilder);

    String transformedString =
        coerceAndStringify("$(exe :ManifestGenerator) $OUT", graphBuilder.requireRule(buildTarget));

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
    BuildTarget buildTarget =
        BuildTargetFactory.newInstance("//java/com/facebook/util:ManifestGenerator");
    createConverter(buildTarget);
    BuildRule buildRule =
        new JavaBinaryRuleBuilder(buildTarget)
            .setMainClass("com.facebook.util.ManifestGenerator")
            .build(graphBuilder);

    // Interpolate the build target in the genrule cmd string.
    String transformedString = coerceAndStringify("$(exe :ManifestGenerator) $OUT", buildRule);

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

  private String coerceAndStringify(String input, BuildRule rule) throws CoerceFailedException {
    StringWithMacros stringWithMacros =
        (StringWithMacros)
            new DefaultTypeCoercerFactory()
                .typeCoercerForType(StringWithMacros.class)
                .coerce(
                    cellPathResolver,
                    filesystem,
                    rule.getBuildTarget().getBasePath(),
                    EmptyTargetConfiguration.INSTANCE,
                    input);
    Arg arg = converter.convert(stringWithMacros, graphBuilder);
    return Arg.stringify(
        arg, DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder)));
  }

  @Test
  public void testBuildTimeDependencies() throws Exception {
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
        new NoopBinaryBuildRule(target, new FakeProjectFilesystem(), params, tool));

    // Verify that the correct cmd was created.
    ExecutableMacroExpander expander = new ExecutableMacroExpander();
    CellPathResolver cellRoots = TestCellBuilder.createCellRoots(filesystem);
    ExecutableMacro executableMacro = ExecutableMacro.of(target);
    assertEquals(
        ToolArg.of(tool), expander.expandFrom(target, cellRoots, graphBuilder, executableMacro));
    Arg expanded = expander.expandFrom(target, cellRoots, graphBuilder, executableMacro);
    assertThat(expanded, Matchers.instanceOf(ToolArg.class));
    assertEquals(tool, ((ToolArg) expanded).getTool());
  }

  @Test
  public void extractRuleKeyAppendable() throws MacroException {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params = TestBuildRuleParams.create();
    Tool tool = new CommandTool.Builder().addArg("command").build();
    graphBuilder.addToIndex(new NoopBinaryBuildRule(target, filesystem, params, tool));
    ExecutableMacroExpander expander = new ExecutableMacroExpander();
    CellPathResolver cellRoots = TestCellBuilder.createCellRoots(filesystem);
    assertThat(
        expander.expandFrom(target, cellRoots, graphBuilder, ExecutableMacro.of(target)),
        Matchers.equalTo(ToolArg.of(tool)));
  }

  private static class NoopBinaryBuildRule extends NoopBuildRuleWithDeclaredAndExtraDeps
      implements BinaryBuildRule {

    private final Tool tool;

    public NoopBinaryBuildRule(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams params,
        Tool tool) {
      super(buildTarget, projectFilesystem, params);
      this.tool = tool;
    }

    @Override
    public Tool getExecutableCommand() {
      return tool;
    }
  }
}
