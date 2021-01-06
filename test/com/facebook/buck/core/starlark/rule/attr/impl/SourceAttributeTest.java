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

package com.facebook.buck.core.starlark.rule.attr.impl;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.SourceArtifact;
import com.facebook.buck.core.artifact.SourceArtifactImpl;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.rules.actions.ActionRegistryForTests;
import com.facebook.buck.core.rules.analysis.impl.FakeRuleAnalysisContextImpl;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.LegacyProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.collect.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystemFactory;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.step.impl.TestActionExecutionRunner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class SourceAttributeTest {

  private final FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final CellNameResolver cellNameResolver =
      TestCellPathResolver.get(filesystem).getCellNameResolver();
  private final TestActionExecutionRunner runner =
      new TestActionExecutionRunner(
          new FakeProjectFilesystemFactory(),
          filesystem,
          BuildTargetFactory.newInstance("//some:rule"));

  private final SourceAttribute attr = ImmutableSourceAttribute.of(Runtime.NONE, "", true);

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void coercesProperly() throws CoerceFailedException {
    BuildTarget target = BuildTargetFactory.newInstance("//foo/bar:baz");

    SourcePath expectedSource = PathSourcePath.of(filesystem, Paths.get("foo", "bar.cpp"));
    SourcePath expectedTarget = DefaultBuildTargetSourcePath.of(target);

    SourcePath coercedSource =
        attr.getValue(
            cellNameResolver,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "foo/bar.cpp");
    SourcePath coercedTarget =
        attr.getValue(
            cellNameResolver,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "//foo/bar:baz");

    assertEquals(expectedSource, coercedSource);
    assertEquals(expectedTarget, coercedTarget);
  }

  @Test
  public void failsMandatoryCoercionProperly() throws CoerceFailedException {
    thrown.expect(CoerceFailedException.class);

    attr.getValue(
        cellNameResolver,
        filesystem,
        ForwardRelativePath.of(""),
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        1);
  }

  @Test
  public void failsMandatoryCoercionIfNoneProvided() throws CoerceFailedException {
    thrown.expect(CoerceFailedException.class);

    attr.getValue(
        cellNameResolver,
        filesystem,
        ForwardRelativePath.of(""),
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        Runtime.NONE);
  }

  @Test
  public void doesNotAllowAbsolutePaths() throws CoerceFailedException {
    thrown.expect(CoerceFailedException.class);
    thrown.expectMessage("cannot contain an absolute path");

    String absolutePathString = filesystem.resolve("foo").toAbsolutePath().toString();

    attr.getValue(
        cellNameResolver,
        filesystem,
        ForwardRelativePath.of(""),
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        absolutePathString);
  }

  @Test
  public void failsTransformIfInvalidCoercedTypeProvided() {

    thrown.expect(IllegalStateException.class);

    attr.getPostCoercionTransform()
        .postCoercionTransform(1, new FakeRuleAnalysisContextImpl(ImmutableMap.of()));
  }

  @Test
  public void failsTransformIfInvalidElementProvided() {

    thrown.expect(IllegalStateException.class);

    attr.getPostCoercionTransform()
        .postCoercionTransform("invalid", new FakeRuleAnalysisContextImpl(ImmutableMap.of()));
  }

  @Test
  public void failsTransformIfElementMissingFromDeps() throws CoerceFailedException {

    SourcePath coerced =
        attr.getValue(
            cellNameResolver,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "//foo:bar");

    thrown.expect(IllegalStateException.class);
    attr.getPostCoercionTransform()
        .postCoercionTransform(coerced, new FakeRuleAnalysisContextImpl(ImmutableMap.of()));
  }

  @Test
  public void failsTransformIfMissingDefaultInfo() throws CoerceFailedException {
    SourcePath coerced =
        attr.getValue(
            cellNameResolver,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "//foo:bar");

    thrown.expect(IllegalStateException.class);
    attr.getPostCoercionTransform()
        .postCoercionTransform(
            coerced,
            new FakeRuleAnalysisContextImpl(
                ImmutableMap.of(
                    BuildTargetFactory.newInstance("//foo:bar"),
                    LegacyProviderInfoCollectionImpl.of())));
  }

  @Test
  public void failsTransformIfZeroOutputFiles() throws CoerceFailedException {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ImmutableDefaultInfo defaultInfo =
        new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableList.of());

    ImmutableMap<BuildTarget, ProviderInfoCollection> deps =
        ImmutableMap.of(target, ProviderInfoCollectionImpl.builder().build(defaultInfo));

    SourcePath coercedTarget =
        attr.getValue(
            cellNameResolver,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "//foo:bar");

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("must have exactly one output");
    attr.getPostCoercionTransform()
        .postCoercionTransform(coercedTarget, new FakeRuleAnalysisContextImpl(deps));
  }

  @Test
  public void failsTransformIfMultipleOutputFiles() throws CoerceFailedException {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ActionRegistryForTests registry = new ActionRegistryForTests(target, filesystem);
    Artifact artifact1 = registry.declareArtifact(Paths.get("baz1"));
    Artifact artifact2 = registry.declareArtifact(Paths.get("baz2"));
    ImmutableDefaultInfo defaultInfo =
        new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableList.of(artifact1, artifact2));

    ImmutableMap<BuildTarget, ProviderInfoCollection> deps =
        ImmutableMap.of(target, ProviderInfoCollectionImpl.builder().build(defaultInfo));

    SourcePath coercedTarget =
        attr.getValue(
            cellNameResolver,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "//foo:bar");

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("must have exactly one output");
    attr.getPostCoercionTransform()
        .postCoercionTransform(coercedTarget, new FakeRuleAnalysisContextImpl(deps));
  }

  @Test
  public void transformsToArtifact() throws CoerceFailedException {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ActionRegistryForTests registry = new ActionRegistryForTests(target, filesystem);
    Artifact buildArtifact1 = registry.declareArtifact(Paths.get("baz1"));
    SourceArtifact sourceArtifact =
        SourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("src", "main.cpp")));
    ImmutableDefaultInfo defaultInfo =
        new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableList.of(buildArtifact1));

    ImmutableMap<BuildTarget, ProviderInfoCollection> deps =
        ImmutableMap.of(target, ProviderInfoCollectionImpl.builder().build(defaultInfo));

    SourcePath coercedSource =
        attr.getValue(
            cellNameResolver,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "src/main.cpp");

    SourcePath coercedTarget =
        attr.getValue(
            cellNameResolver,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "//foo:bar");

    Object transformedSource =
        attr.getPostCoercionTransform()
            .postCoercionTransform(coercedSource, new FakeRuleAnalysisContextImpl(deps));
    Object transformedTarget =
        attr.getPostCoercionTransform()
            .postCoercionTransform(coercedTarget, new FakeRuleAnalysisContextImpl(deps));

    assertEquals(sourceArtifact, transformedSource);
    assertEquals(buildArtifact1, transformedTarget);
  }
}
