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

package com.facebook.buck.rules;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.util.FileHashCache;
import com.google.common.collect.Maps;
import com.netflix.astyanax.AstyanaxContext;
import com.netflix.astyanax.Keyspace;

import org.junit.Test;

public class CassandraArtifactCacheTest {

  @Test
  @SuppressWarnings("unchecked") // Needed to mock generic class.
  public void whenCacheClosedThenContextShutdown() {
    AstyanaxContext<Keyspace> mockContext = createMock(AstyanaxContext.class);
    BuckEventBus mockEventBus = createMock(BuckEventBus.class);
    FileHashCache fileHashCache =
        FakeFileHashCache.createFromStrings(Maps.<String, String>newHashMap());
    mockContext.shutdown();
    replay(mockContext);
    CassandraArtifactCache cache = new CassandraArtifactCache(
        10 /* timeoutSeconds */,
        true /* doStore */,
        mockEventBus,
        fileHashCache,
        mockContext);
    cache.close();
    verify(mockContext);
  }
}
