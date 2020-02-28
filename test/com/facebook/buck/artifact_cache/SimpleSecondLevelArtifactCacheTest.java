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

package com.facebook.buck.artifact_cache;

import static org.junit.Assert.assertThat;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.util.concurrent.Futures;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class SimpleSecondLevelArtifactCacheTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testFetchStored() throws IOException {
    ProjectFilesystem fs = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();

    try (InMemoryArtifactCache inMemoryArtifactCache = new InMemoryArtifactCache()) {
      SimpleSecondLevelArtifactCache cache =
          new SimpleSecondLevelArtifactCache(inMemoryArtifactCache, fs, eventBus);

      LazyPath dummyFile = LazyPath.ofInstance(tmp.newFile());

      String contentKey =
          Futures.getUnchecked(
              cache.storeAsync(
                  ArtifactInfo.builder().build(),
                  BorrowablePath.notBorrowablePath(dummyFile.get())));

      assertThat(
          Futures.getUnchecked(cache.fetchAsync(null, contentKey, dummyFile)).getType(),
          Matchers.equalTo(CacheResultType.HIT));
    }
  }
}
