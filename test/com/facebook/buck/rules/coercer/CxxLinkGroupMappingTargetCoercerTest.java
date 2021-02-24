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

package com.facebook.buck.rules.coercer;

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.linkgroup.CxxLinkGroupMappingTarget;
import com.facebook.buck.core.linkgroup.CxxLinkGroupMappingTargetLabelMatcher;
import com.facebook.buck.core.linkgroup.CxxLinkGroupMappingTargetPatternMatcher;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.ConstantHostTargetConfigurationResolver;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class CxxLinkGroupMappingTargetCoercerTest {
  private final ProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final ForwardRelativePath basePath =
      ForwardRelativePath.of("java/com/facebook/buck/example");
  private CxxLinkGroupMappingTargetCoercer coercer;

  public static CxxLinkGroupMappingTargetCoercer buildTypeCoercer(
      TypeCoercer<CxxLinkGroupMappingTarget.Traversal, CxxLinkGroupMappingTarget.Traversal>
          traversalTypeCoercer) {
    UnconfiguredBuildTargetTypeCoercer unconfigured =
        new UnconfiguredBuildTargetTypeCoercer(new ParsingUnconfiguredBuildTargetViewFactory());
    TypeCoercer<UnconfiguredBuildTarget, BuildTarget> buildTargetTypeCoercer =
        new BuildTargetTypeCoercer(unconfigured);
    return new CxxLinkGroupMappingTargetCoercer(
        buildTargetTypeCoercer, traversalTypeCoercer, new PatternTypeCoercer());
  }

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Before
  public void setUp() {
    CxxLinkGroupMappingTargetTraversalCoercer traversalCoercer =
        CxxLinkGroupMappingTargetTraversalCoercerTest.buildTypeCoercer();
    coercer = buildTypeCoercer(traversalCoercer);
  }

  @Test
  public void canCoerceMappingWithTreeTraversalKeyword() throws CoerceFailedException {
    String targetString = "//foo:bar";
    ImmutableList<Object> input = ImmutableList.of(targetString, "tree");
    CxxLinkGroupMappingTarget target =
        coercer.coerceBoth(
            createCellRoots(filesystem).getCellNameResolver(),
            filesystem,
            basePath,
            UnconfiguredTargetConfiguration.INSTANCE,
            new ConstantHostTargetConfigurationResolver(UnconfiguredTargetConfiguration.INSTANCE),
            input);
    assertEquals(target.getBuildTarget().getFullyQualifiedName(), targetString);
    assertEquals(target.getTraversal(), CxxLinkGroupMappingTarget.Traversal.TREE);
  }

  @Test
  public void canCoerceMappingWithNodeTraversalKeyword() throws CoerceFailedException {
    String targetString = "//foo:bar";
    ImmutableList<Object> input = ImmutableList.of(targetString, "node");
    CxxLinkGroupMappingTarget target =
        coercer.coerceBoth(
            createCellRoots(filesystem).getCellNameResolver(),
            filesystem,
            basePath,
            UnconfiguredTargetConfiguration.INSTANCE,
            new ConstantHostTargetConfigurationResolver(UnconfiguredTargetConfiguration.INSTANCE),
            input);
    assertEquals(target.getBuildTarget().getFullyQualifiedName(), targetString);
    assertEquals(target.getTraversal(), CxxLinkGroupMappingTarget.Traversal.NODE);
  }

  @Test
  public void canCoerceMappingWithLabelRegex() throws CoerceFailedException {
    String targetString = "//foo:bar";
    String labelRegex = "some_label_regex*";
    String labelMatch = "label:" + labelRegex;
    ImmutableList<Object> input = ImmutableList.of(targetString, "tree", labelMatch);
    CxxLinkGroupMappingTarget target =
        coercer.coerceBoth(
            createCellRoots(filesystem).getCellNameResolver(),
            filesystem,
            basePath,
            UnconfiguredTargetConfiguration.INSTANCE,
            new ConstantHostTargetConfigurationResolver(UnconfiguredTargetConfiguration.INSTANCE),
            input);
    assertEquals(target.getBuildTarget().getFullyQualifiedName(), targetString);
    assertEquals(target.getTraversal(), CxxLinkGroupMappingTarget.Traversal.TREE);
    assertTrue(target.getMatcher().isPresent());
    assertTrue(target.getMatcher().get() instanceof CxxLinkGroupMappingTargetLabelMatcher);

    assertEquals(
        ((CxxLinkGroupMappingTargetLabelMatcher) target.getMatcher().get())
            .getLabelPattern()
            .toString(),
        labelRegex);
  }

  @Test
  public void canCoerceMappingWithTargetPattern() throws CoerceFailedException {
    String targetString = "//foo:bar";
    String buildTargetPattern = "//foo/...";
    String targetPattern = "pattern:" + buildTargetPattern;
    ImmutableList<Object> input = ImmutableList.of(targetString, "tree", targetPattern);
    CxxLinkGroupMappingTarget target =
        coercer.coerceBoth(
            createCellRoots(filesystem).getCellNameResolver(),
            filesystem,
            basePath,
            UnconfiguredTargetConfiguration.INSTANCE,
            new ConstantHostTargetConfigurationResolver(UnconfiguredTargetConfiguration.INSTANCE),
            input);
    assertEquals(target.getBuildTarget().getFullyQualifiedName(), targetString);
    assertEquals(target.getTraversal(), CxxLinkGroupMappingTarget.Traversal.TREE);
    assertTrue(target.getMatcher().isPresent());
    assertTrue(target.getMatcher().get() instanceof CxxLinkGroupMappingTargetPatternMatcher);

    assertEquals(
        ((CxxLinkGroupMappingTargetPatternMatcher) target.getMatcher().get()).getPattern(),
        buildTargetPattern);
  }

  @Test
  public void throwsOnInvalidMappingLabel() throws CoerceFailedException {
    String targetString = "//foo:bar";
    String label = "foorbar";
    ImmutableList<Object> input = ImmutableList.of(targetString, "tree", label);

    expectedException.expectMessage(
        "Third element must be a string starting with label: or pattern:");

    coercer.coerceBoth(
        createCellRoots(filesystem).getCellNameResolver(),
        filesystem,
        basePath,
        UnconfiguredTargetConfiguration.INSTANCE,
        new ConstantHostTargetConfigurationResolver(UnconfiguredTargetConfiguration.INSTANCE),
        input);
  }
}
