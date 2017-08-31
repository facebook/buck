/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.distributed;

import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.not;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.google.common.util.concurrent.Futures;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MultiSourceContentsProviderTest {

  private static final long FUTURE_GET_TIMEOUT_SECONDS = 5;

  @Rule public TemporaryFolder tempDir = new TemporaryFolder();

  private InlineContentsProvider mockInlineProvider;
  private LocalFsContentsProvider mockLocalFsContentsProvider;
  private ServerContentsProvider mockServerContentsProvider;
  private FileMaterializationStatsTracker mockStatsTracker;

  @Before
  public void setUp() {
    mockInlineProvider = EasyMock.createMock(InlineContentsProvider.class);
    mockLocalFsContentsProvider = EasyMock.createMock(LocalFsContentsProvider.class);
    mockServerContentsProvider = EasyMock.createMock(ServerContentsProvider.class);
    mockStatsTracker = EasyMock.createMock(FileMaterializationStatsTracker.class);
  }

  @Test
  public void testOrderOfContentProvidersAndStatsTracking()
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    Path targetAbsPath = tempDir.getRoot().toPath().resolve("my_file.txt");
    BuildJobStateFileHashEntry entry1 = new BuildJobStateFileHashEntry().setSha1("1234");
    BuildJobStateFileHashEntry entry2 = new BuildJobStateFileHashEntry().setSha1("2345");
    BuildJobStateFileHashEntry entry3 = new BuildJobStateFileHashEntry().setSha1("3456");
    BuildJobStateFileHashEntry entry4 = new BuildJobStateFileHashEntry().setSha1("4567");

    // Let entry1 be a hit in the InlineProvider, and the rest be misses.
    expect(mockInlineProvider.materializeFileContentsAsync(entry1, targetAbsPath))
        .andReturn(Futures.immediateFuture(true))
        .once();
    expect(
            mockInlineProvider.materializeFileContentsAsync(
                anyObject(BuildJobStateFileHashEntry.class), eq(targetAbsPath)))
        .andReturn(Futures.immediateFuture(false))
        .times(3);

    // Let entry2 be a hit in the LocalFsProvider, and the rest be misses.
    expect(mockLocalFsContentsProvider.materializeFileContentsAsync(entry2, targetAbsPath))
        .andReturn(Futures.immediateFuture(true))
        .once();
    expect(
            mockLocalFsContentsProvider.materializeFileContentsAsync(
                not(eq(entry2)), eq(targetAbsPath)))
        .andReturn(Futures.immediateFuture(false))
        .times(2);

    // Let entry3 be a hit in the ServerContentsProvider, and the rest be misses.
    // As a result, entry3 will also be cached into the LocalFsProvider.
    expect(mockServerContentsProvider.materializeFileContentsAsync(entry3, targetAbsPath))
        .andReturn(Futures.immediateFuture(true))
        .once();
    mockLocalFsContentsProvider.writeFileAndGetInputStream(entry3, targetAbsPath);
    expectLastCall().once();
    expect(
            mockServerContentsProvider.materializeFileContentsAsync(
                not(eq(entry3)), eq(targetAbsPath)))
        .andReturn(Futures.immediateFuture(false))
        .times(1);

    mockInlineProvider.close();
    expectLastCall().once();
    mockLocalFsContentsProvider.close();
    expectLastCall().once();
    mockServerContentsProvider.close();
    expectLastCall().once();

    // Only one file from LocalFsProvider.
    mockStatsTracker.recordLocalFileMaterialized();
    expectLastCall().once();
    // Only one file from ServerContentsProvider.
    mockStatsTracker.recordRemoteFileMaterialized(anyLong());
    expectLastCall().once();

    replay(mockInlineProvider);
    replay(mockLocalFsContentsProvider);
    replay(mockServerContentsProvider);
    replay(mockStatsTracker);

    try (MultiSourceContentsProvider provider =
        new MultiSourceContentsProvider(
            mockInlineProvider,
            Optional.of(mockLocalFsContentsProvider),
            mockServerContentsProvider,
            mockStatsTracker)) {
      provider
          .materializeFileContentsAsync(entry1, targetAbsPath)
          .get(FUTURE_GET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      provider
          .materializeFileContentsAsync(entry2, targetAbsPath)
          .get(FUTURE_GET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      provider
          .materializeFileContentsAsync(entry3, targetAbsPath)
          .get(FUTURE_GET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      provider
          .materializeFileContentsAsync(entry4, targetAbsPath)
          .get(FUTURE_GET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    verify(mockInlineProvider);
    verify(mockLocalFsContentsProvider);
    verify(mockServerContentsProvider);
    verify(mockStatsTracker);
  }
}
