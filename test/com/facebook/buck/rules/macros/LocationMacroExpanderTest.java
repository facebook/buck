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

import static com.facebook.buck.rules.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.facebook.buck.model.MacroException;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaBinaryRuleBuilder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class LocationMacroExpanderTest {

  private BuildRule createSampleJavaBinaryRule(BuildRuleResolver ruleResolver)
      throws NoSuchBuildTargetException {
    // Create a java_binary that depends on a java_library so it is possible to create a
    // java_binary rule with a classpath entry and a main class.
    BuildRule javaLibrary = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/facebook/util:util"))
        .addSrc(Paths.get("java/com/facebook/util/ManifestGenerator.java"))
        .build(ruleResolver);

    BuildTarget buildTarget =
        BuildTargetFactory.newInstance("//java/com/facebook/util:ManifestGenerator");
    return new JavaBinaryRuleBuilder(buildTarget)
        .setDeps(ImmutableSortedSet.of(javaLibrary.getBuildTarget()))
        .setMainClass("com.facebook.util.ManifestGenerator")
        .build(ruleResolver);
  }

  @Test
  public void testShouldWarnUsersWhenThereIsNoOutputForARuleButLocationRequested()
      throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//cheese:java"))
        .build(resolver);
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:cake");

    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    MacroHandler macroHandler = new MacroHandler(
        ImmutableMap.<String, MacroExpander>of(
            "location",
            new LocationMacroExpander()));
    try {
      macroHandler.expand(
          target,
          createCellRoots(filesystem),
          resolver,
          "$(location //cheese:java)");
      fail("Location was null. Expected HumanReadableException with helpful message.");
    } catch (MacroException e) {
      assertEquals(
          "expanding $(location //cheese:java): //cheese:java used" +
              " in location macro does not produce output",
          e.getMessage());
    }
  }

  @Test
  public void replaceLocationOfFullyQualifiedBuildTarget() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule javaBinary = createSampleJavaBinaryRule(ruleResolver);
    Path outputPath = javaBinary.getPathToOutput();
    Path absolutePath = outputPath.toAbsolutePath();

    String originalCmd = String.format(
        "$(location :%s) $(location %s) $OUT",
        javaBinary.getBuildTarget().getShortNameAndFlavorPostfix(),
        javaBinary.getBuildTarget().getFullyQualifiedName());

    // Interpolate the build target in the genrule cmd string.
    MacroHandler macroHandler = new MacroHandler(
        ImmutableMap.<String, MacroExpander>of(
            "location",
            new LocationMacroExpander()));
    String transformedString = macroHandler.expand(
        javaBinary.getBuildTarget(),
        createCellRoots(filesystem),
        ruleResolver,
        originalCmd);

    // Verify that the correct cmd was created.
    String expectedCmd = String.format("%s %s $OUT", absolutePath, absolutePath);
    assertEquals(expectedCmd, transformedString);
  }

  @Test
  public void extractRuleKeyAppendable() throws MacroException {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    LocationMacroExpander macroExpander = new LocationMacroExpander();
    assertThat(
        macroExpander.extractRuleKeyAppendables(
            target,
            createCellRoots(new FakeProjectFilesystem()),
            resolver,
            "//some/other:rule"),
        Matchers.<Object>equalTo(
            new BuildTargetSourcePath(BuildTargetFactory.newInstance("//some/other:rule"))));
  }

}
