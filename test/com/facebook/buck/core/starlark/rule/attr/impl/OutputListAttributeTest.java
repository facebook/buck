/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.starlark.rule.attr.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.ArtifactDeclarationException;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.rules.actions.ActionRegistryForTests;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystemFactory;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.step.impl.TestActionExecutionRunner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.syntax.Runtime;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class OutputListAttributeTest {

  private final FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final CellPathResolver cellRoots = TestCellPathResolver.get(filesystem);
  private final TestActionExecutionRunner runner =
      new TestActionExecutionRunner(
          new FakeProjectFilesystemFactory(),
          filesystem,
          BuildTargetFactory.newInstance("//some:rule"));

  private final OutputListAttribute attr =
      new ImmutableOutputListAttribute(ImmutableList.of(), "", true, true);

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void coercesProperly() throws CoerceFailedException {
    ImmutableList<String> coercedPaths =
        attr.getValue(
            cellRoots,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            ImmutableList.of("foo/bar.cpp", "foo/baz.cpp"));

    assertEquals(ImmutableList.of("foo/bar.cpp", "foo/baz.cpp"), coercedPaths);
  }

  @Test
  public void failsMandatoryCoercionProperly() throws CoerceFailedException {
    thrown.expect(CoerceFailedException.class);

    attr.getValue(
        cellRoots,
        filesystem,
        ForwardRelativePath.of(""),
        UnconfiguredTargetConfiguration.INSTANCE,
        1);
  }

  @Test
  public void failsMandatoryCoercionIfNoneProvided() throws CoerceFailedException {
    thrown.expect(CoerceFailedException.class);

    attr.getValue(
        cellRoots,
        filesystem,
        ForwardRelativePath.of(""),
        UnconfiguredTargetConfiguration.INSTANCE,
        Runtime.NONE);
  }

  @Test
  public void failsTransformIfInvalidCoercedTypeProvided() {
    thrown.expect(IllegalArgumentException.class);

    attr.getPostCoercionTransform()
        .postCoercionTransform(1, runner.getRegistry(), ImmutableMap.of());
  }

  @Test
  public void failsTransformationOnInvalidPath() throws Throwable {
    thrown.expect(ArtifactDeclarationException.class);

    ImmutableList<String> value =
        attr.getValue(
            cellRoots,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            ImmutableList.of("foo/baz.cpp", "foo/bar\0"));
    attr.getPostCoercionTransform()
        .postCoercionTransform(value, runner.getRegistry(), ImmutableMap.of());
  }

  @Test
  public void failsTransformationOnAbsolutePath() throws CoerceFailedException {
    thrown.expect(ArtifactDeclarationException.class);

    ImmutableList<String> value =
        attr.getValue(
            cellRoots,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            ImmutableList.of("foo/baz.cpp", Paths.get("").toAbsolutePath().toString()));
    attr.getPostCoercionTransform()
        .postCoercionTransform(value, runner.getRegistry(), ImmutableMap.of());
  }

  @Test
  public void failsTransformationOnParentPath() throws CoerceFailedException {
    thrown.expect(ArtifactDeclarationException.class);

    ImmutableList<String> value =
        attr.getValue(
            cellRoots,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            ImmutableList.of("foo/baz.cpp", "../foo.txt"));
    attr.getPostCoercionTransform()
        .postCoercionTransform(value, runner.getRegistry(), ImmutableMap.of());
  }

  @Test
  public void transformsToArtifact() throws CoerceFailedException {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ActionRegistryForTests registry = new ActionRegistryForTests(target, filesystem);

    ImmutableList<String> outputPaths =
        attr.getValue(
            cellRoots,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            ImmutableList.of("subdir/other.cpp", "main.cpp"));
    Object coerced =
        attr.getPostCoercionTransform()
            .postCoercionTransform(outputPaths, registry, ImmutableMap.of());

    assertThat(coerced, Matchers.instanceOf(ImmutableList.class));
    ImmutableList<Artifact> artifacts = (ImmutableList<Artifact>) coerced;
    assertEquals(2, artifacts.size());

    for (Artifact artifact : artifacts) {
      assertFalse(artifact.isBound());
      assertFalse(artifact.isSource());
    }
    assertEquals(
        ImmutableList.of(
            Paths.get("foo", "bar__", "subdir", "other.cpp").toString(),
            Paths.get("foo", "bar__", "main.cpp").toString()),
        artifacts.stream().map(Artifact::getShortPath).collect(ImmutableList.toImmutableList()));
  }

  @Test
  public void failsIfEmptyListProvidedAndNotAllowed() throws CoerceFailedException {
    thrown.expect(CoerceFailedException.class);
    thrown.expectMessage("may not be empty");

    OutputListAttribute attr =
        new ImmutableOutputListAttribute(ImmutableList.of(), "", true, false);

    attr.getValue(
        cellRoots,
        filesystem,
        ForwardRelativePath.of(""),
        UnconfiguredTargetConfiguration.INSTANCE,
        ImmutableList.of());
  }

  @Test
  public void succeedsIfEmptyListProvidedAndAllowed() throws CoerceFailedException {
    ImmutableList<String> value =
        attr.getValue(
            cellRoots,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            ImmutableList.of());
    assertTrue(value.isEmpty());
  }
}
