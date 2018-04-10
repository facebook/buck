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
import static org.junit.Assert.fail;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaBinaryRuleBuilder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasSupplementaryOutputs;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.SortedSet;
import javax.annotation.Nullable;
import org.junit.Test;

public class LocationMacroExpanderTest {

  private BuildRule createSampleJavaBinaryRule(BuildRuleResolver ruleResolver)
      throws NoSuchBuildTargetException {
    // Create a java_binary that depends on a java_library so it is possible to create a
    // java_binary rule with a classpath entry and a main class.
    BuildRule javaLibrary =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/facebook/util:util"))
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
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//cheese:java"))
        .build(resolver);
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:cake");

    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    MacroHandler macroHandler =
        new MacroHandler(ImmutableMap.of("location", new LocationMacroExpander()));
    try {
      macroHandler.expand(
          target, createCellRoots(filesystem), resolver, "$(location //cheese:java)");
      fail("Location was null. Expected HumanReadableException with helpful message.");
    } catch (MacroException e) {
      assertEquals(
          "expanding $(location //cheese:java): //cheese:java used"
              + " in location macro does not produce output",
          e.getMessage());
    }
  }

  @Test
  public void replaceLocationOfFullyQualifiedBuildTarget() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver ruleResolver = new TestBuildRuleResolver();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(ruleResolver));
    BuildRule javaBinary = createSampleJavaBinaryRule(ruleResolver);
    Path absolutePath = pathResolver.getAbsolutePath(javaBinary.getSourcePathToOutput());

    String originalCmd =
        String.format(
            "$(location :%s) $(location %s) $OUT",
            javaBinary.getBuildTarget().getShortNameAndFlavorPostfix(),
            javaBinary.getBuildTarget().getFullyQualifiedName());

    // Interpolate the build target in the genrule cmd string.
    MacroHandler macroHandler =
        new MacroHandler(ImmutableMap.of("location", new LocationMacroExpander()));
    String transformedString =
        macroHandler.expand(
            javaBinary.getBuildTarget(), createCellRoots(filesystem), ruleResolver, originalCmd);

    // Verify that the correct cmd was created.
    String expectedCmd = String.format("%s %s $OUT", absolutePath, absolutePath);
    assertEquals(expectedCmd, transformedString);
  }

  @Test
  public void replaceSupplementalOutputLocation() throws Exception {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem("/some_root");
    BuildTarget target = BuildTargetFactory.newInstance(filesystem.getRootPath(), "//foo:bar");
    BuildRuleResolver ruleResolver = new TestBuildRuleResolver();
    ruleResolver.addToIndex(new RuleWithSupplementaryOutput(target, filesystem));

    String input = "$(location //foo:bar[sup])";
    MacroHandler macroHandler =
        new MacroHandler(ImmutableMap.of("location", new LocationMacroExpander()));
    String transformedString =
        macroHandler.expand(target, createCellRoots(filesystem), ruleResolver, input);
    assertEquals("/some_root/supplementary-sup", transformedString);
  }

  @Test
  public void missingLocationArgumentThrows() {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem("/some_root");
    BuildTarget target = BuildTargetFactory.newInstance(filesystem.getRootPath(), "//foo:bar");
    BuildRuleResolver ruleResolver = new TestBuildRuleResolver();
    ruleResolver.addToIndex(new RuleWithSupplementaryOutput(target, filesystem));

    String input = "$(location )";
    MacroHandler macroHandler =
        new MacroHandler(ImmutableMap.of("location", new LocationMacroExpander()));
    try {
      macroHandler.expand(target, createCellRoots(filesystem), ruleResolver, input);
      fail("Location was empty. Expected MacroException");
    } catch (MacroException e) {
      assertEquals("expanding $(location ): expected a single argument: []", e.getMessage());
    }
  }

  private final class RuleWithSupplementaryOutput extends AbstractBuildRule
      implements HasSupplementaryOutputs {

    public RuleWithSupplementaryOutput(
        BuildTarget buildTarget, ProjectFilesystem projectFilesystem) {
      super(buildTarget, projectFilesystem);
    }

    @Override
    public SourcePath getSourcePathToSupplementaryOutput(String name) {
      return ExplicitBuildTargetSourcePath.of(
          getBuildTarget(), getProjectFilesystem().getPath("supplementary-" + name));
    }

    @Override
    public SortedSet<BuildRule> getBuildDeps() {
      return ImmutableSortedSet.of();
    }

    @Override
    public ImmutableList<? extends Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      return ImmutableList.of();
    }

    @Nullable
    @Override
    public SourcePath getSourcePathToOutput() {
      return null;
    }
  }
}
