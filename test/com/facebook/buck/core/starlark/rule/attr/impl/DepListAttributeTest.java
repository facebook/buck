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
import static org.junit.Assert.assertTrue;

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
import com.google.devtools.build.lib.syntax.SkylarkDict;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DepListAttributeTest {

  private final FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final CellNameResolver cellNameResolver =
      TestCellPathResolver.get(filesystem).getCellNameResolver();
  private final TestActionExecutionRunner runner =
      new TestActionExecutionRunner(
          new FakeProjectFilesystemFactory(),
          filesystem,
          BuildTargetFactory.newInstance("//some:rule"));

  private final DepListAttribute attr =
      ImmutableDepListAttribute.of(ImmutableList.of(), "", true, true, ImmutableList.of());

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void coercesListsProperly() throws CoerceFailedException {
    BuildTarget target = BuildTargetFactory.newInstance("//foo/bar:baz");
    ImmutableList<BuildTarget> expected = ImmutableList.of(target);

    ImmutableList<BuildTarget> coerced =
        attr.getValue(
            cellNameResolver,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            ImmutableList.of("//foo/bar:baz"));

    assertEquals(expected, coerced);
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
        "foo");
  }

  @Test
  public void failsMandatoryCoercionWithWrongListType() throws CoerceFailedException {
    thrown.expect(CoerceFailedException.class);

    attr.getValue(
        cellNameResolver,
        filesystem,
        ForwardRelativePath.of(""),
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        ImmutableList.of(1));
  }

  @Test
  public void failsIfEmptyListProvidedAndNotAllowed() throws CoerceFailedException {
    DepListAttribute attr =
        ImmutableDepListAttribute.of(ImmutableList.of(), "", true, false, ImmutableList.of());

    thrown.expect(CoerceFailedException.class);
    thrown.expectMessage("may not be empty");

    attr.getValue(
        cellNameResolver,
        filesystem,
        ForwardRelativePath.of(""),
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        ImmutableList.of());
  }

  @Test
  public void succeedsIfEmptyListProvidedAndAllowed() throws CoerceFailedException {

    ImmutableList<BuildTarget> value =
        attr.getValue(
            cellNameResolver,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            ImmutableList.of());
    assertTrue(value.isEmpty());
  }

  @Test
  public void failsTransformIfInvalidCoercedTypeProvided() {

    thrown.expect(VerifyException.class);

    attr.getPostCoercionTransform()
        .postCoercionTransform("invalid", new FakeRuleAnalysisContextImpl(ImmutableMap.of()));
  }

  @Test
  public void failsTransformIfInvalidElementInList() {

    thrown.expect(VerifyException.class);

    attr.getPostCoercionTransform()
        .postCoercionTransform(
            ImmutableList.of("invalid"), new FakeRuleAnalysisContextImpl(ImmutableMap.of()));
  }

  @Test
  public void failsTransformIfElementMissingFromDeps() throws CoerceFailedException {

    ImmutableList<BuildTarget> coerced =
        attr.getValue(
            cellNameResolver,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            ImmutableList.of("//foo:bar"));

    thrown.expect(NullPointerException.class);
    attr.getPostCoercionTransform()
        .postCoercionTransform(coerced, new FakeRuleAnalysisContextImpl(ImmutableMap.of()));
  }

  @Test
  public void failsTransformIfMissingRequiredProvider() throws CoerceFailedException {
    FakeBuiltInProvider expectedProvider = new FakeBuiltInProvider("expected");
    DepListAttribute attr =
        ImmutableDepListAttribute.of(
            ImmutableList.of(), "", true, true, ImmutableList.of(expectedProvider));
    FakeBuiltInProvider presentProvider = new FakeBuiltInProvider("present");

    FakeInfo info = new FakeInfo(presentProvider);

    ImmutableList<BuildTarget> coerced =
        attr.getValue(
            cellNameResolver,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            ImmutableList.of("//foo:bar"));

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
  public void transformsToListOfProviderInfoCollections() throws CoerceFailedException {
    BuildTarget dep1 = BuildTargetFactory.newInstance("//foo:bar");
    BuildTarget dep2 = BuildTargetFactory.newInstance("//foo:baz");

    ActionRegistryForTests registry = new ActionRegistryForTests(dep1, filesystem);
    Artifact buildArtifact1 = registry.declareArtifact(Paths.get("out1"));
    Artifact buildArtifact2 = registry.declareArtifact(Paths.get("out2"));

    ActionRegistryForTests registry2 = new ActionRegistryForTests(dep2, filesystem);
    Artifact buildArtifact3 = registry2.declareArtifact(Paths.get("out3"));
    Artifact buildArtifact4 = registry2.declareArtifact(Paths.get("out4"));

    ImmutableDefaultInfo defaultInfo1 =
        new ImmutableDefaultInfo(
            SkylarkDict.empty(), ImmutableList.of(buildArtifact1, buildArtifact2));
    ImmutableDefaultInfo defaultInfo2 =
        new ImmutableDefaultInfo(
            SkylarkDict.empty(), ImmutableList.of(buildArtifact3, buildArtifact4));

    ImmutableMap<BuildTarget, ProviderInfoCollection> deps =
        ImmutableMap.of(
            dep1,
            ProviderInfoCollectionImpl.builder().build(defaultInfo1),
            dep2,
            ProviderInfoCollectionImpl.builder().build(defaultInfo2));

    ImmutableList<BuildTarget> coerced =
        attr.getValue(
            cellNameResolver,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            ImmutableList.of("//foo:bar", "//foo:baz"));

    List<SkylarkDependency> transformed =
        attr.getPostCoercionTransform()
            .postCoercionTransform(coerced, new FakeRuleAnalysisContextImpl(deps));

    assertEquals(2, transformed.size());
    assertEquals(
        defaultInfo1, transformed.get(0).getProviderInfos().get(DefaultInfo.PROVIDER).get());
    assertEquals(
        defaultInfo2, transformed.get(1).getProviderInfos().get(DefaultInfo.PROVIDER).get());
    assertEquals("//foo:bar", transformed.get(0).label().getCanonicalForm());
    assertEquals("//foo:baz", transformed.get(1).label().getCanonicalForm());
  }
}
