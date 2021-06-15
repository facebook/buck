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

package com.facebook.buck.skylark.io.impl;

import static org.junit.Assert.*;

import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanTestUtils;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.Rule;
import org.junit.Test;

public class HybridGlobberFactoryTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void globberFactoryRootIsSubdirectoryOfWatchmanRoot() throws Exception {
    tmp.newFolder("foo");
    tmp.newFolder("foo/bar");
    tmp.newFile("foo/bar/baz.txt");

    WatchmanTestUtils.setupWatchman(tmp.getRoot());

    try (Watchman watchman =
        WatchmanTestUtils.buildWatchmanAssumeNotNull(tmp.getRoot().resolve("foo"))) {
      HybridGlobberFactory globberFactory =
          HybridGlobberFactory.using(watchman, tmp.getRoot().resolve("foo"));
      assertEquals(
          ImmutableSet.of("baz.txt"),
          globberFactory
              .create(ForwardRelPath.of("bar"))
              .run(ImmutableList.of("*.txt"), ImmutableList.of(), false));
    }
  }
}
