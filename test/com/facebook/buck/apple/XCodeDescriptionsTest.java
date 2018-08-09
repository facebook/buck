/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.apple;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import org.junit.Before;
import org.junit.Test;

public class XCodeDescriptionsTest {

  @Before
  public void setUp() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
  }

  @Test
  public void testAppleLibraryIsXcodeTargetDescription() {
    Cell rootCell = (new TestCellBuilder()).build();
    BuildTarget libraryTarget = BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "lib");
    TargetNode<AppleLibraryDescriptionArg> library =
        AppleLibraryBuilder.createBuilder(libraryTarget).setSrcs(ImmutableSortedSet.of()).build();

    XCodeDescriptions xcodeDescriptions =
        XCodeDescriptionsFactory.create(BuckPluginManagerFactory.createPluginManager());

    assertTrue(xcodeDescriptions.isXcodeDescription(library.getDescription()));
  }

  @Test
  public void testIosResourceIsNotXcodeTargetDescription() {
    Cell rootCell = (new TestCellBuilder()).build();
    BuildTarget resourceTarget = BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "res");
    TargetNode<?> resourceNode =
        AppleResourceBuilder.createBuilder(resourceTarget)
            .setFiles(ImmutableSet.of())
            .setDirs(ImmutableSet.of())
            .build();

    XCodeDescriptions xcodeDescriptions =
        XCodeDescriptionsFactory.create(BuckPluginManagerFactory.createPluginManager());

    assertFalse(xcodeDescriptions.isXcodeDescription(resourceNode.getDescription()));
  }
}
