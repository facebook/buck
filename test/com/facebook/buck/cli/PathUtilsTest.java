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

package com.facebook.buck.cli;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.PathReferenceRule;
import com.facebook.buck.core.rules.impl.PathReferenceRuleWithMultipleOutputs;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class PathUtilsTest {
  @Rule public final ExpectedException exception = ExpectedException.none();

  private final ProjectFilesystem fileSystem = new FakeProjectFilesystem();
  private ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
  private SourcePathResolverAdapter pathResolver =
      new SourcePathResolverAdapter(DefaultSourcePathResolver.from(graphBuilder));

  @Test
  public void resolvesWithBuckCompat() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    BuildRule rule =
        new PathReferenceRule(
            buildTarget,
            fileSystem,
            fileSystem.getBuckPaths().getConfiguredBuckOut().resolve("foo"));
    graphBuilder.addToIndex(rule);
    Path expected = fileSystem.resolve(fileSystem.getBuckPaths().getBuckOut()).resolve("foo");

    assertThat(
        PathUtils.getUserFacingOutputPath(
                pathResolver, rule, true, OutputLabel.defaultLabel(), true)
            .get(),
        Matchers.equalTo(expected));
  }

  @Test
  public void resolvesWithoutBuckCompat() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    BuildRule rule = new PathReferenceRule(buildTarget, fileSystem, Paths.get("foo"));
    graphBuilder.addToIndex(rule);
    AbsPath expected = fileSystem.getRootPath().resolve("foo");

    assertThat(
        PathUtils.getUserFacingOutputPath(
                pathResolver, rule, false, OutputLabel.defaultLabel(), true)
            .get(),
        Matchers.equalTo(expected.getPath()));
  }

  @Test
  public void resolvesOutputLabel() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    BuildRule rule =
        new PathReferenceRuleWithMultipleOutputs(
            buildTarget,
            new FakeProjectFilesystem(),
            null,
            ImmutableMap.of(
                OutputLabel.of("baz"),
                ImmutableSet.of(Paths.get("baz")),
                OutputLabel.of("bar"),
                ImmutableSet.of(Paths.get("bar")),
                OutputLabel.of("qux"),
                ImmutableSet.of(Paths.get(("qux")))));
    graphBuilder.addToIndex(rule);
    AbsPath expected = fileSystem.getRootPath().resolve("bar");

    assertThat(
        PathUtils.getUserFacingOutputPath(pathResolver, rule, false, OutputLabel.of("bar"), true)
            .get(),
        Matchers.equalTo(expected.getPath()));
  }

  @Test
  public void returnsEmptyIfRequestedLabelDoesNotExist() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    BuildRule rule =
        new PathReferenceRuleWithMultipleOutputs(
            buildTarget,
            new FakeProjectFilesystem(),
            null,
            ImmutableMap.of(
                OutputLabel.of("baz"),
                ImmutableSet.of(Paths.get("baz")),
                OutputLabel.of("bar"),
                ImmutableSet.of(Paths.get("bar")),
                OutputLabel.of("qux"),
                ImmutableSet.of(Paths.get(("qux")))));
    graphBuilder.addToIndex(rule);

    assertFalse(
        PathUtils.getUserFacingOutputPath(pathResolver, rule, false, OutputLabel.of("dne"), true)
            .isPresent());
  }

  @Test
  public void throwsIfMultipleOutputsNotSupported() {
    exception.expect(IllegalStateException.class);
    exception.expectMessage(
        containsString("Multiple outputs not supported for path_reference_rule target //:foo"));

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    BuildRule rule = new PathReferenceRule(buildTarget, fileSystem, Paths.get("foo"));
    graphBuilder.addToIndex(rule);

    PathUtils.getUserFacingOutputPath(pathResolver, rule, false, OutputLabel.of("label"), true);
  }

  @Test
  public void throwsIfShowOutputsFlagNotPassedForNonDefaultLabel() {
    exception.expect(HumanReadableException.class);
    exception.expectMessage(
        containsString(
            "path_reference_rule_with_multiple_outputs target //:foo[label] should use --show-outputs"));

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    BuildRule rule =
        new PathReferenceRuleWithMultipleOutputs(
            buildTarget,
            fileSystem,
            Paths.get("foo"),
            ImmutableMap.of(OutputLabel.of("foo"), ImmutableSet.of(Paths.get("foo"))));
    graphBuilder.addToIndex(rule);

    PathUtils.getUserFacingOutputPath(pathResolver, rule, false, OutputLabel.of("label"), false)
        .get();
  }

  @Test
  public void getsDefaultOutputPathForRulesSupportingMultipleOutputsWithoutShowOutputsFlag() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    BuildRule rule =
        new PathReferenceRuleWithMultipleOutputs(
            buildTarget, fileSystem, Paths.get("bar"), ImmutableMap.of());
    graphBuilder.addToIndex(rule);
    AbsPath expected = fileSystem.getRootPath().resolve("bar");

    assertThat(
        PathUtils.getUserFacingOutputPath(
                pathResolver, rule, false, OutputLabel.defaultLabel(), false)
            .get(),
        Matchers.equalTo(expected.getPath()));
  }
}
