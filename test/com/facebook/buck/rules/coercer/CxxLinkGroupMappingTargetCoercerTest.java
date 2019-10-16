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

package com.facebook.buck.rules.coercer;

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.linkgroup.CxxLinkGroupMappingTarget;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Test;

public class CxxLinkGroupMappingTargetCoercerTest {
  private final ProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final Path basePath = Paths.get("java/com/facebook/buck/example");
  private CxxLinkGroupMappingTargetCoercer coercer;

  public static CxxLinkGroupMappingTargetCoercer buildTypeCoercer(
      TypeCoercer<CxxLinkGroupMappingTarget.Traversal> traversalTypeCoercer) {
    TypeCoercer<UnconfiguredBuildTargetView> unconfigured =
        new UnconfiguredBuildTargetTypeCoercer(new ParsingUnconfiguredBuildTargetViewFactory());
    TypeCoercer<BuildTarget> buildTargetTypeCoercer = new BuildTargetTypeCoercer(unconfigured);
    return new CxxLinkGroupMappingTargetCoercer(buildTargetTypeCoercer, traversalTypeCoercer);
  }

  @Before
  public void setUp() {
    TypeCoercer<CxxLinkGroupMappingTarget.Traversal> traversalCoercer =
        CxxLinkGroupMappingTargetTraversalCoercerTest.buildTypeCoercer();
    coercer = buildTypeCoercer(traversalCoercer);
  }

  @Test
  public void canCoerceMappingWithTreeTraversalKeyword() throws CoerceFailedException {
    String targetString = "//foo:bar";
    ImmutableList<Object> input = ImmutableList.of(targetString, "tree");
    CxxLinkGroupMappingTarget target =
        coercer.coerce(
            createCellRoots(filesystem),
            filesystem,
            basePath,
            UnconfiguredTargetConfiguration.INSTANCE,
            input);
    assertEquals(target.getBuildTarget().getFullyQualifiedName(), targetString);
    assertEquals(target.getTraversal(), CxxLinkGroupMappingTarget.Traversal.TREE);
  }

  @Test
  public void canCoerceMappingWithNodeTraversalKeyword() throws CoerceFailedException {
    String targetString = "//foo:bar";
    ImmutableList<Object> input = ImmutableList.of(targetString, "node");
    CxxLinkGroupMappingTarget target =
        coercer.coerce(
            createCellRoots(filesystem),
            filesystem,
            basePath,
            UnconfiguredTargetConfiguration.INSTANCE,
            input);
    assertEquals(target.getBuildTarget().getFullyQualifiedName(), targetString);
    assertEquals(target.getTraversal(), CxxLinkGroupMappingTarget.Traversal.NODE);
  }
}
