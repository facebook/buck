/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.rules.macros;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.PathReferenceRuleWithMultipleOutputs;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavaBinaryRuleBuilder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeToken;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class LocationMacroExpanderTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  private ProjectFilesystem filesystem;
  private ActionGraphBuilder graphBuilder;
  private CellNameResolver cellNameResolver;
  private StringWithMacrosConverter converter;

  private ActionGraphBuilder setup(ProjectFilesystem projectFilesystem, BuildTarget buildTarget) {
    cellNameResolver = TestCellBuilder.createCellRoots(projectFilesystem).getCellNameResolver();
    graphBuilder = new TestActionGraphBuilder();
    converter =
        StringWithMacrosConverter.of(
            buildTarget,
            cellNameResolver,
            graphBuilder,
            ImmutableList.of(LocationMacroExpander.INSTANCE));
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
    Path absolutePath =
        graphBuilder.getSourcePathResolver().getAbsolutePath(javaRule.getSourcePathToOutput());
    String expectedCmd = String.format("%s %s $OUT", absolutePath, absolutePath);

    assertEquals(expectedCmd, transformedString);
  }

  @Test
  public void replaceSupplementalOutputLocation() throws Exception {
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem("/some_root");
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar");
    graphBuilder = setup(filesystem, buildTarget);
    BuildRule rule = new RuleWithSupplementaryOutput(buildTarget, filesystem);
    graphBuilder.addToIndex(rule);

    String transformedString = coerceAndStringify("$(location //foo:bar[sup])", rule);

    assertEquals(
        filesystem.getRootPath().resolve("supplementary-sup").toString(), transformedString);
  }

  @Test
  public void replaceOutputLabelOutputLocation() throws Exception {
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem("/some_root");
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar");
    graphBuilder = setup(filesystem, buildTarget);
    BuildRule rule =
        new PathReferenceRuleWithMultipleOutputs(
            buildTarget,
            filesystem,
            Paths.get("incorrect"),
            ImmutableMap.of(OutputLabel.of("label"), ImmutableSet.of(Paths.get("pathpathpath"))));
    graphBuilder.addToIndex(rule);

    String transformedString = coerceAndStringify("$(location //foo:bar[label])", rule);

    assertEquals(filesystem.getRootPath().resolve("pathpathpath").toString(), transformedString);
  }

  @Test
  public void throwsExceptionWhenCannotFindNamedOutputs() throws Exception {
    thrown.expect(HumanReadableException.class);
    thrown.expectCause(Matchers.instanceOf(MacroException.class));
    thrown.expectMessage(
        "//foo:bar used in location macro does not produce outputs with label [nonexistent]");

    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem("/some_root");
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar");
    graphBuilder = setup(filesystem, buildTarget);
    BuildRule rule =
        new PathReferenceRuleWithMultipleOutputs(
            buildTarget, filesystem, Paths.get("incorrect"), ImmutableMap.of());
    graphBuilder.addToIndex(rule);

    coerceAndStringify("$(location //foo:bar[nonexistent])", rule);
  }

  @Test
  public void throwsExceptionWhenRetrieveMultipleOutputs() throws Exception {
    thrown.expect(HumanReadableException.class);
    thrown.expectCause(Matchers.instanceOf(MacroException.class));
    thrown.expectMessage(
        "//foo:bar[label] produces multiple outputs but location macro accepts only one output");

    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem("/some_root");
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar");
    graphBuilder = setup(filesystem, buildTarget);
    BuildRule rule =
        new PathReferenceRuleWithMultipleOutputs(
            buildTarget,
            filesystem,
            Paths.get("incorrect"),
            ImmutableMap.of(
                OutputLabel.of("label"), ImmutableSet.of(Paths.get("path1"), Paths.get("path2"))));
    graphBuilder.addToIndex(rule);

    coerceAndStringify("$(location //foo:bar[label])", rule);
  }

  @Test
  public void missingLocationArgumentThrows() throws Exception {
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem("/some_root");
    cellNameResolver = TestCellBuilder.createCellRoots(filesystem).getCellNameResolver();

    thrown.expect(CoerceFailedException.class);
    thrown.expectMessage(
        allOf(
            containsString("The macro '$(location )' could not be expanded:"),
            containsString("expected exactly one argument (found 1)")));

    new DefaultTypeCoercerFactory()
        .typeCoercerForType(TypeToken.of(StringWithMacros.class))
        .coerceBoth(
            cellNameResolver,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "$(location )");
  }

  private String coerceAndStringify(String input, BuildRule rule) throws CoerceFailedException {
    StringWithMacros stringWithMacros =
        new DefaultTypeCoercerFactory()
            .typeCoercerForType(TypeToken.of(StringWithMacros.class))
            .coerceBoth(
                cellNameResolver,
                filesystem,
                rule.getBuildTarget().getCellRelativeBasePath().getPath(),
                UnconfiguredTargetConfiguration.INSTANCE,
                UnconfiguredTargetConfiguration.INSTANCE,
                input);
    Arg arg = converter.convert(stringWithMacros);
    return Arg.stringify(arg, graphBuilder.getSourcePathResolver());
  }
}
