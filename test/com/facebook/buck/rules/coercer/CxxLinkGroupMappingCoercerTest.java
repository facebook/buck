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

import com.facebook.buck.core.linkgroup.CxxLinkGroupMapping;
import com.facebook.buck.core.linkgroup.CxxLinkGroupMappingTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Test;

public class CxxLinkGroupMappingCoercerTest {
  private final ProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final ForwardRelativePath basePath =
      ForwardRelativePath.of("java/com/facebook/buck/example");
  private CxxLinkGroupMappingCoercer coercer;

  public static CxxLinkGroupMappingCoercer buildTypeCoercer(
      TypeCoercer<Object, CxxLinkGroupMappingTarget> targetTypeCoercer) {
    return new CxxLinkGroupMappingCoercer(
        new StringTypeCoercer(), new ListTypeCoercer<>(targetTypeCoercer));
  }

  @Before
  public void setUp() {
    TypeCoercer<Object, CxxLinkGroupMappingTarget.Traversal> traversalCoercer =
        CxxLinkGroupMappingTargetTraversalCoercerTest.buildTypeCoercer();
    TypeCoercer<Object, CxxLinkGroupMappingTarget> targetTypeCoercer =
        CxxLinkGroupMappingTargetCoercerTest.buildTypeCoercer(traversalCoercer);
    coercer = buildTypeCoercer(targetTypeCoercer);
  }

  @Test
  public void canCoerceMappingWithTreeTraversalKeyword() throws CoerceFailedException {
    String fooBarTargetString = "//foo:bar";
    String fooBazTargetString = "//foo:baz";
    String linkGroup = "app";

    ImmutableList<Object> fooBarMapping = ImmutableList.of(fooBarTargetString, "tree");
    ImmutableList<Object> fooBazMapping = ImmutableList.of(fooBazTargetString, "node");
    ImmutableList<Object> targetMappings = ImmutableList.of(fooBarMapping, fooBazMapping);
    ImmutableList<Object> linkGroupMap = ImmutableList.of(linkGroup, targetMappings);

    CxxLinkGroupMapping mapping =
        coercer.coerce(
            createCellRoots(filesystem).getCellNameResolver(),
            filesystem,
            basePath,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            linkGroupMap);

    assertEquals(mapping.getLinkGroup(), linkGroup);

    ImmutableList<CxxLinkGroupMappingTarget> coercedMappingTargets = mapping.getMappingTargets();
    assertEquals(coercedMappingTargets.size(), targetMappings.size());

    CxxLinkGroupMappingTarget fooBarTargetMapping = coercedMappingTargets.get(0);
    assertEquals(fooBarTargetMapping.getBuildTarget().getFullyQualifiedName(), fooBarTargetString);
    assertEquals(fooBarTargetMapping.getTraversal(), CxxLinkGroupMappingTarget.Traversal.TREE);

    CxxLinkGroupMappingTarget fooBazTargetMapping = coercedMappingTargets.get(1);
    assertEquals(fooBazTargetMapping.getBuildTarget().getFullyQualifiedName(), fooBazTargetString);
    assertEquals(fooBazTargetMapping.getTraversal(), CxxLinkGroupMappingTarget.Traversal.NODE);
  }
}
