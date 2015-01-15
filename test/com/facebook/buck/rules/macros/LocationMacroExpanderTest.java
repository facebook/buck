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
import static org.junit.Assert.fail;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.JavaBinaryRuleBuilder;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class LocationMacroExpanderTest {

  private BuildRule createSampleJavaBinaryRule(BuildRuleResolver ruleResolver) {
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
  public void testShouldWarnUsersWhenThereIsNoOutputForARuleButLocationRequested() {
    BuildTargetParser parser = new BuildTargetParser();
    BuildRuleResolver resolver = new BuildRuleResolver();
    JavaLibraryBuilder
        .createBuilder(BuildTarget.builder("//cheese", "java").build())
        .build(resolver);
    BuildTarget target = BuildTarget.builder("//cheese", "cake").build();

    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    MacroHandler macroHandler = new MacroHandler(
        ImmutableMap.<String, MacroExpander>of(
            "location",
            new LocationMacroExpander(parser)));
    try {
      macroHandler.expand(target, resolver, filesystem, "$(location //cheese:java)");
      fail("Location was null. Expected HumanReadableException with helpful message.");
    } catch (MacroException e) {
      assertEquals(
          "expanding $(location //cheese:java): //cheese:java used" +
              " in location macro does not produce output",
          e.getMessage());
    }
  }

  @Test
  public void replaceLocationOfFullyQualifiedBuildTarget() throws IOException, MacroException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTargetParser parser = new BuildTargetParser();
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    BuildRule javaBinary = createSampleJavaBinaryRule(ruleResolver);
    Path outputPath = javaBinary.getPathToOutputFile();
    Path absolutePath = outputPath.toAbsolutePath();

    String originalCmd = String.format(
        "$(location :%s) $(location %s) $OUT",
        javaBinary.getBuildTarget().getShortNameAndFlavorPostfix(),
        javaBinary.getBuildTarget().getFullyQualifiedName());

    // Interpolate the build target in the genrule cmd string.
    MacroHandler macroHandler = new MacroHandler(
        ImmutableMap.<String, MacroExpander>of(
            "location",
            new LocationMacroExpander(parser)));
    String transformedString = macroHandler.expand(
        javaBinary.getBuildTarget(),
        ruleResolver,
        filesystem,
        originalCmd);

    // Verify that the correct cmd was created.
    String expectedCmd = String.format("%s %s $OUT", absolutePath, absolutePath);
    assertEquals(expectedCmd, transformedString);
  }

}
