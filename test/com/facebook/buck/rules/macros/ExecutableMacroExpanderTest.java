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

import static com.facebook.buck.util.BuckConstant.GEN_DIR;
import static org.junit.Assert.assertEquals;

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

import java.nio.file.Path;
import java.nio.file.Paths;

public class ExecutableMacroExpanderTest {

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
  public void testReplaceBinaryBuildRuleRefsInCmd() throws MacroException {
    BuildTargetParser parser = new BuildTargetParser();
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    BuildTarget target = BuildTarget.builder("//cheese", "cake").build();
    createSampleJavaBinaryRule(ruleResolver);
    String originalCmd = "$(exe //java/com/facebook/util:ManifestGenerator) $OUT";

    // Interpolate the build target in the genrule cmd string.
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    MacroHandler macroHandler = new MacroHandler(
        ImmutableMap.<String, MacroExpander>of(
            "exe",
            new ExecutableMacroExpander(parser)));
    String transformedString = macroHandler.expand(target, ruleResolver, filesystem, originalCmd);

    // Verify that the correct cmd was created.
    Path expectedClasspath = Paths.get(GEN_DIR + "/java/com/facebook/util/ManifestGenerator.jar")
        .toAbsolutePath();

    String expectedCmd = String.format(
        "java -jar %s $OUT",
        expectedClasspath);
    assertEquals(expectedCmd, transformedString);
  }

  @Test
  public void testReplaceRelativeBinaryBuildRuleRefsInCmd() throws MacroException {
    BuildTargetParser parser = new BuildTargetParser();
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    BuildRule rule = createSampleJavaBinaryRule(ruleResolver);
    String originalCmd = "$(exe :ManifestGenerator) $OUT";

    // Interpolate the build target in the genrule cmd string.
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    MacroHandler macroHandler = new MacroHandler(
        ImmutableMap.<String, MacroExpander>of(
            "exe",
            new ExecutableMacroExpander(parser)));
    String transformedString = macroHandler.expand(
        rule.getBuildTarget(),
        ruleResolver,
        filesystem,
        originalCmd);

    // Verify that the correct cmd was created.
    Path expectedClasspath = Paths.get(GEN_DIR + "/java/com/facebook/util/ManifestGenerator.jar")
        .toAbsolutePath();
    String expectedCmd = String.format(
        "java -jar %s $OUT",
        expectedClasspath);
    assertEquals(expectedCmd, transformedString);
  }

  @Test
  public void testDepsGenrule() throws MacroException {
    BuildTargetParser parser = new BuildTargetParser();
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    BuildRule rule = createSampleJavaBinaryRule(ruleResolver);

    // Interpolate the build target in the genrule cmd string.
    String originalCmd = "$(exe :ManifestGenerator) $OUT";

    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    MacroHandler macroHandler = new MacroHandler(
        ImmutableMap.<String, MacroExpander>of(
            "exe",
            new ExecutableMacroExpander(parser)));
    String transformedString = macroHandler.expand(
        rule.getBuildTarget(),
        ruleResolver,
        filesystem,
        originalCmd);

    // Verify that the correct cmd was created.
    Path expectedClasspath = Paths.get(GEN_DIR + "/java/com/facebook/util/ManifestGenerator.jar")
        .toAbsolutePath();
    String expectedCmd = String.format(
        "java -jar %s $OUT",
        expectedClasspath);
    assertEquals(expectedCmd, transformedString);
  }

}
