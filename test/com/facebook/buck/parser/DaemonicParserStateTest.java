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

package com.facebook.buck.parser;

import static org.junit.Assert.*;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.io.watchman.WatchmanEvent;
import com.facebook.buck.io.watchman.WatchmanPathEvent;
import com.facebook.buck.io.watchman.WatchmanWatcherOneBigEvent;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.junit.Test;

public class DaemonicParserStateTest {

  @Test
  public void invalidation() {
    DaemonicParserState parser = new DaemonicParserState();

    PipelineNodeCache.Cache<ForwardRelPath, BuildFileManifest> manifestCache =
        parser.getRawNodeCache();

    Cell cell = new TestCellBuilder().build().getRootCell();

    DaemonicParserValidationToken validationToken = parser.validationToken();

    assertEquals(
        Optional.empty(),
        manifestCache.lookupComputedNode(cell, ForwardRelPath.of("foo"), validationToken));

    BuildFileManifest manifest =
        BuildFileManifest.of(
            TwoArraysImmutableHashMap.of(),
            ImmutableSet.of(),
            ImmutableMap.of(),
            ImmutableList.of(),
            ImmutableList.of());

    manifestCache.putComputedNodeIfNotPresent(
        cell, ForwardRelPath.of("foo"), manifest, false, validationToken);

    assertEquals(
        Optional.of(manifest),
        manifestCache.lookupComputedNode(cell, ForwardRelPath.of("foo"), validationToken));

    parser.invalidateBasedOn(
        WatchmanWatcherOneBigEvent.pathEvent(
            WatchmanPathEvent.of(
                cell.getRoot(), WatchmanEvent.Kind.DELETE, ForwardRelPath.of("another-path"))));

    // The whole cache is invalidated now
    assertEquals(
        Optional.empty(),
        manifestCache.lookupComputedNode(cell, ForwardRelPath.of("foo"), validationToken));

    DaemonicParserValidationToken newToken = parser.validationToken();

    // But with new invalidation token cache data is stil available
    assertEquals(
        Optional.of(manifest),
        manifestCache.lookupComputedNode(cell, ForwardRelPath.of("foo"), newToken));
  }
}
