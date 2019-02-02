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

package com.facebook.buck.rules.macros;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.core.HasMavenCoordinates;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class MavenCoordinatesMacroExpanderTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  private ActionGraphBuilder graphBuilder;
  private MavenCoordinatesMacroExpander expander;

  @Before
  public void setUp() {
    graphBuilder = new TestActionGraphBuilder();
    expander = new MavenCoordinatesMacroExpander();
  }

  @Test
  public void testHasMavenCoordinatesBuildRule() {

    String mavenCoords = "org.foo:bar:1.0";

    BuildRule rule =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//test:java"))
            .setMavenCoords(mavenCoords)
            .build(graphBuilder);
    try {
      String actualCoords = expander.getMavenCoordinates(rule);
      assertEquals(
          "Return maven coordinates do not match provides ones", mavenCoords, actualCoords);
    } catch (MacroException e) {
      fail(String.format("Unexpected MacroException: %s", e.getMessage()));
    }
  }

  @Test
  public void testNonHasMavenCoordinatesBuildRule() {
    assumeFalse(
        "Assuming that FakeBuildRule does not have maven coordinates",
        FakeBuildRule.class.isAssignableFrom(HasMavenCoordinates.class));

    BuildRule rule = new FakeBuildRule("//test:foo");

    try {
      expander.getMavenCoordinates(rule);
      fail("Expected MacroException; Rule does not contain maven coordinates");
    } catch (MacroException e) {
      assertTrue(
          "Expected MacroException that indicates target does not have maven coordinates",
          e.getMessage().contains("does not correspond to a rule with maven coordinates"));
    }
  }

  @Test
  public void testHasMavenCoordinatesBuildRuleMissingCoordinates() {
    BuildRule rule =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//test:no-mvn"))
            .build(graphBuilder);
    try {
      expander.getMavenCoordinates(rule);
      fail("Expected MacroException; Rule does not contain maven coordinates");
    } catch (MacroException e) {
      assertTrue(
          "Expected MacroException that indicates target does not have maven coordinates",
          e.getMessage().contains("does not have maven coordinates"));
    }
  }

  @Test
  public void testExpansionOfMavenCoordinates() throws Exception {
    String mavenCoords = "org.foo:bar:1.0";
    BuildTarget target = BuildTargetFactory.newInstance("//:java");
    BuildRule rule =
        JavaLibraryBuilder.createBuilder(target).setMavenCoords(mavenCoords).build(graphBuilder);

    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CellPathResolver cellPathResolver = TestCellBuilder.createCellRoots(filesystem);
    StringWithMacrosConverter converter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(target)
            .setCellPathResolver(cellPathResolver)
            .addExpanders(expander)
            .build();

    String input = "$(maven_coords //:java)";

    String expansion = coerceAndStringify(filesystem, cellPathResolver, converter, input, rule);

    assertEquals("Return maven coordinates do not match provides ones", mavenCoords, expansion);
  }

  @Test
  public void testMissingBuildRule() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//:java");
    BuildRule rule = JavaLibraryBuilder.createBuilder(target).build(graphBuilder);

    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CellPathResolver cellPathResolver = TestCellBuilder.createCellRoots(filesystem);
    StringWithMacrosConverter converter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(target)
            .setCellPathResolver(cellPathResolver)
            .addExpanders(expander)
            .build();

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("no rule //:foo");

    coerceAndStringify(filesystem, cellPathResolver, converter, "$(maven_coords //:foo)", rule);
  }

  private String coerceAndStringify(
      ProjectFilesystem filesystem,
      CellPathResolver cellPathResolver,
      StringWithMacrosConverter converter,
      String input,
      BuildRule rule)
      throws CoerceFailedException {
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
}
