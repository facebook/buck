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
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.rules.actions.ActionRegistryForTests;
import com.facebook.buck.core.rules.analysis.impl.FakeBuiltInProvider;
import com.facebook.buck.core.rules.analysis.impl.FakeInfo;
import com.facebook.buck.core.rules.analysis.impl.FakeRuleAnalysisContextImpl;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.collect.impl.TestProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.lib.DefaultInfo;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.facebook.buck.core.starlark.rule.data.SkylarkDependency;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystemFactory;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.step.impl.TestActionExecutionRunner;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DepAttributeTest {

  private final FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final CellNameResolver cellRoots =
      TestCellPathResolver.get(filesystem).getCellNameResolver();
  private final TestActionExecutionRunner runner =
      new TestActionExecutionRunner(
          new FakeProjectFilesystemFactory(),
          filesystem,
          BuildTargetFactory.newInstance("//some:rule"));

  private final DepAttribute attr =
      ImmutableDepAttribute.of(Runtime.NONE, "", true, ImmutableList.of());

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void coercesProperly() throws CoerceFailedException {
    BuildTarget target = BuildTargetFactory.newInstance("//foo/bar:baz");

    BuildTarget coercedTarget =
        attr.getValue(
            cellRoots,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "//foo/bar:baz");

    assertEquals(target, coercedTarget);
  }

  @Test
  public void failsMandatoryCoercionProperly() throws CoerceFailedException {
    thrown.expect(CoerceFailedException.class);

    attr.getValue(
        cellRoots,
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
        cellRoots,
        filesystem,
        ForwardRelativePath.of(""),
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        Runtime.NONE);
  }

  @Test
  public void failsTransformIfInvalidCoercedTypeProvided() {

    thrown.expect(VerifyException.class);

    attr.getPostCoercionTransform()
        .postCoercionTransform(1, new FakeRuleAnalysisContextImpl(ImmutableMap.of()));
  }

  @Test
  public void failsTransformIfInvalidElementProvided() {

    thrown.expect(VerifyException.class);

    attr.getPostCoercionTransform()
        .postCoercionTransform("invalid", new FakeRuleAnalysisContextImpl(ImmutableMap.of()));
  }

  @Test
  public void failsTransformIfElementMissingFromDeps() throws CoerceFailedException {

    BuildTarget coerced =
        attr.getValue(
            cellRoots,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "//foo:bar");

    thrown.expect(NullPointerException.class);
    attr.getPostCoercionTransform()
        .postCoercionTransform(coerced, new FakeRuleAnalysisContextImpl(ImmutableMap.of()));
  }

  @Test
  public void failsTransformIfMissingRequiredProvider() throws CoerceFailedException {
    FakeBuiltInProvider expectedProvider = new FakeBuiltInProvider("expected");
    DepAttribute attr =
        ImmutableDepAttribute.of(Runtime.NONE, "", true, ImmutableList.of(expectedProvider));
    FakeBuiltInProvider presentProvider = new FakeBuiltInProvider("present");

    FakeInfo info = new FakeInfo(presentProvider);

    BuildTarget coerced =
        attr.getValue(
            cellRoots,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "//foo:bar");

    thrown.expect(VerifyException.class);
    attr.getPostCoercionTransform()
        .postCoercionTransform(
            coerced,
            new FakeRuleAnalysisContextImpl(
                ImmutableMap.of(
                    BuildTargetFactory.newInstance("//foo:bar"),
                    TestProviderInfoCollectionImpl.builder().put(info).build())));
  }

  @Test
  public void transformsToProviderInfoCollection() throws CoerceFailedException {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ActionRegistryForTests registry = new ActionRegistryForTests(target, filesystem);
    Artifact buildArtifact = registry.declareArtifact(Paths.get("baz1"));
    ImmutableDefaultInfo defaultInfo =
        new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableList.of(buildArtifact));

    ImmutableMap<BuildTarget, ProviderInfoCollection> deps =
        ImmutableMap.of(target, ProviderInfoCollectionImpl.builder().build(defaultInfo));

    BuildTarget coerced =
        attr.getValue(
            cellRoots,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "//foo:bar");

    SkylarkDependency dependency =
        attr.getPostCoercionTransform()
            .postCoercionTransform(coerced, new FakeRuleAnalysisContextImpl(deps));

    assertEquals(
        buildArtifact,
        Iterables.getOnlyElement(
            dependency.getProviderInfos().get(DefaultInfo.PROVIDER).get().defaultOutputs()));
    assertEquals("//foo:bar", dependency.label().getCanonicalForm());
  }
}
