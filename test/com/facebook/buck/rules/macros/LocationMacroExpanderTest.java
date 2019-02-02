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

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasSupplementaryOutputs;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavaBinaryRuleBuilder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.SortedSet;
import javax.annotation.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class LocationMacroExpanderTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  private ProjectFilesystem filesystem;
  private ActionGraphBuilder graphBuilder;
  private CellPathResolver cellPathResolver;
  private StringWithMacrosConverter converter;

  private ActionGraphBuilder setup(ProjectFilesystem projectFilesystem, BuildTarget buildTarget) {
    cellPathResolver = TestCellBuilder.createCellRoots(projectFilesystem);
    graphBuilder = new TestActionGraphBuilder();
    converter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(buildTarget)
            .setCellPathResolver(cellPathResolver)
            .addExpanders(new LocationMacroExpander())
            .build();
    return graphBuilder;
  }

  @Test
  public void testShouldWarnUsersWhenThereIsNoOutputForARuleButLocationRequested()
      throws Exception {
    filesystem = new FakeProjectFilesystem();
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//cheese:java");
    graphBuilder = setup(filesystem, buildTarget);
    JavaLibraryBuilder.createBuilder(buildTarget).build(graphBuilder);

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "//cheese:java: //cheese:java used in location macro does not produce output");

    coerceAndStringify("$(location //cheese:java)", graphBuilder.requireRule(buildTarget));
  }

  @Test
  public void replaceLocationOfFullyQualifiedBuildTarget() throws Exception {
    BuildTarget buildTarget =
        BuildTargetFactory.newInstance("//java/com/facebook/util:ManifestGenerator");
    filesystem = new FakeProjectFilesystem();
    graphBuilder = setup(filesystem, buildTarget);
    BuildRule javaRule =
        new JavaBinaryRuleBuilder(buildTarget)
            .setMainClass("com.facebook.util.ManifestGenerator")
            .build(graphBuilder);

    String originalCmd =
        String.format(
            "$(location :%s) $(location %s) $OUT",
            buildTarget.getShortNameAndFlavorPostfix(), buildTarget.getFullyQualifiedName());

    String transformedString = coerceAndStringify(originalCmd, javaRule);

    // Verify that the correct cmd was created.
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    Path absolutePath = pathResolver.getAbsolutePath(javaRule.getSourcePathToOutput());
    String expectedCmd = String.format("%s %s $OUT", absolutePath, absolutePath);

    assertEquals(expectedCmd, transformedString);
  }

  @Test
  public void replaceSupplementalOutputLocation() throws Exception {
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem("/some_root");
    BuildTarget buildTarget = BuildTargetFactory.newInstance(filesystem.getRootPath(), "//foo:bar");
    graphBuilder = setup(filesystem, buildTarget);
    BuildRule rule = new RuleWithSupplementaryOutput(buildTarget, filesystem);
    graphBuilder.addToIndex(rule);

    String transformedString = coerceAndStringify("$(location //foo:bar[sup])", rule);

    assertEquals("/some_root/supplementary-sup", transformedString);
  }

  @Test
  public void missingLocationArgumentThrows() throws Exception {
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem("/some_root");
    cellPathResolver = TestCellBuilder.createCellRoots(filesystem);

    thrown.expect(CoerceFailedException.class);
    thrown.expectMessage(
        allOf(
            containsString("The macro '$(location )' could not be expanded:"),
            containsString("expected exactly one argument (found 1)")));

    new DefaultTypeCoercerFactory()
        .typeCoercerForType(StringWithMacros.class)
        .coerce(
            cellPathResolver,
            filesystem,
            Paths.get(""),
            EmptyTargetConfiguration.INSTANCE,
            "$(location )");
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
}
