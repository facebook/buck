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

package com.facebook.buck.io.watchman;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

public class WatchmanTestUtils {
  /** Sync all watchman roots. */
  public static void sync(Watchman watchman)
      throws IOException, InterruptedException, WatchmanQueryFailedException {
    try (WatchmanClient client = watchman.createClient()) {
      for (AbsPath root : watchman.getProjectWatches().keySet()) {
        assertEquals(ImmutableSet.of(root), watchman.getProjectWatches().keySet());
        // synchronize using clock request
        Either<Map<String, Object>, WatchmanClient.Timeout> clockResult =
            client.queryWithTimeout(
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                WatchmanQuery.clock(root.toString(), Optional.of(10000)));
        assertTrue(clockResult.isLeft());
      }
    }
  }
}
