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

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.testutil.FakeExecutor;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ServerContentsProviderTest {

  private static long FUTURE_TIMEOUT_SECONDS = 1;

  private static final String HASH1 = "abcd";
  private static final String HASH2 = "xkcd";
  private static final String HASH3 = "buck";
  private static final String HASH4 = "face";
  private static final String HASH5 = "book";

  private static final String FILE1 = "my";
  private static final String FILE2 = "super";
  private static final String FILE3 = "cool";
  private static final String FILE4 = "contents";

  private DistBuildService distBuildService;
  private ServerContentsProvider provider;
  private FakeExecutor fakeExecutor;
  private FileMaterializationStatsTracker statsTracker;

  @Before
  public void setUp() {
    distBuildService = EasyMock.createMock(DistBuildService.class);
    fakeExecutor = new FakeExecutor();
    statsTracker = EasyMock.createStrictMock(FileMaterializationStatsTracker.class);
  }

  @After
  public void tearDown() {
    if (provider != null) {
      provider.close();
    }
  }

  private void initProvider(long bufferPeriodMs, int bufferMaxSize) {
    provider =
        new ServerContentsProvider(
            distBuildService, fakeExecutor, statsTracker, bufferPeriodMs, bufferMaxSize);
  }

  @Test
  public void testMultiFetchPeriodWorks()
      throws IOException, InterruptedException, ExecutionException, TimeoutException {
    initProvider(1, 100);

    ImmutableMap.Builder<String, byte[]> result1 = new ImmutableMap.Builder<>();
    result1.put(HASH1, FILE1.getBytes(StandardCharsets.UTF_8));
    result1.put(HASH2, FILE2.getBytes(StandardCharsets.UTF_8));
    expect(distBuildService.multiFetchSourceFiles(ImmutableList.of(HASH1, HASH2)))
        .andReturn(result1.build())
        .once();
    statsTracker.recordPeriodicCasMultiFetch(EasyMock.anyLong());
    expectLastCall().once();

    ImmutableMap.Builder<String, byte[]> result2 = new ImmutableMap.Builder<>();
    result2.put(HASH3, FILE3.getBytes(StandardCharsets.UTF_8));
    expect(distBuildService.multiFetchSourceFiles(ImmutableList.of(HASH3)))
        .andReturn(result2.build())
        .once();
    statsTracker.recordPeriodicCasMultiFetch(EasyMock.anyLong());
    expectLastCall().once();
    replay(distBuildService);
    replay(statsTracker);

    Future<byte[]> future1, future2, future3;
    future1 = provider.fetchFileContentsAsync(new BuildJobStateFileHashEntry().setSha1(HASH1));
    future2 = provider.fetchFileContentsAsync(new BuildJobStateFileHashEntry().setSha1(HASH2));
    fakeExecutor.drain();
    future1.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    future2.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

    future3 = provider.fetchFileContentsAsync(new BuildJobStateFileHashEntry().setSha1(HASH3));
    fakeExecutor.drain();
    future3.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

    // Extra run to check for calls with zero HashCodes.
    fakeExecutor.drain();

    verify(distBuildService);
    verify(statsTracker);
    Assert.assertArrayEquals(FILE1.getBytes(StandardCharsets.UTF_8), future1.get());
    Assert.assertArrayEquals(FILE2.getBytes(StandardCharsets.UTF_8), future2.get());
    Assert.assertArrayEquals(FILE3.getBytes(StandardCharsets.UTF_8), future3.get());
  }

  @Test
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void testMultiFetchMaxBufferSizeWorks()
      throws IOException, InterruptedException, ExecutionException, TimeoutException {
    initProvider(1000 * 60 * 60 * 24, 2);

    // We should get request for 2 files first.
    ImmutableMap.Builder<String, byte[]> result1 = new ImmutableMap.Builder<>();
    result1.put(HASH1, FILE1.getBytes(StandardCharsets.UTF_8));
    result1.put(HASH2, FILE2.getBytes(StandardCharsets.UTF_8));
    expect(distBuildService.multiFetchSourceFiles(ImmutableList.of(HASH1, HASH2)))
        .andReturn(result1.build())
        .once();
    statsTracker.recordFullBufferCasMultiFetch(EasyMock.anyLong());
    expectLastCall().once();

    // Then 2 again.
    ImmutableMap.Builder<String, byte[]> result2 = new ImmutableMap.Builder<>();
    result2.put(HASH3, FILE3.getBytes(StandardCharsets.UTF_8));
    result2.put(HASH4, FILE4.getBytes(StandardCharsets.UTF_8));
    expect(distBuildService.multiFetchSourceFiles(ImmutableList.of(HASH3, HASH4)))
        .andReturn(result2.build())
        .once();
    statsTracker.recordFullBufferCasMultiFetch(EasyMock.anyLong());
    expectLastCall().once();

    // One lone request (for HASH5) should never be fetched.
    replay(distBuildService);
    replay(statsTracker);

    Future<byte[]> future1, future2, future3, future4, future5;
    future1 = provider.fetchFileContentsAsync(new BuildJobStateFileHashEntry().setSha1(HASH1));
    future2 = provider.fetchFileContentsAsync(new BuildJobStateFileHashEntry().setSha1(HASH2));
    future3 = provider.fetchFileContentsAsync(new BuildJobStateFileHashEntry().setSha1(HASH3));
    future4 = provider.fetchFileContentsAsync(new BuildJobStateFileHashEntry().setSha1(HASH4));
    future5 = provider.fetchFileContentsAsync(new BuildJobStateFileHashEntry().setSha1(HASH5));
    // We should not need to drain the scheduler.
    // Scheduler is only supposed to be used for periodic cleanup.
    future1.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    future2.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    future3.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    future4.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

    verify(distBuildService);
    verify(statsTracker);
    Assert.assertArrayEquals(FILE1.getBytes(StandardCharsets.UTF_8), future1.get());
    Assert.assertArrayEquals(FILE2.getBytes(StandardCharsets.UTF_8), future2.get());
    Assert.assertArrayEquals(FILE3.getBytes(StandardCharsets.UTF_8), future3.get());
    Assert.assertArrayEquals(FILE4.getBytes(StandardCharsets.UTF_8), future4.get());

    try {
      future5.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      Assert.fail("Timeout was expected.");
    } catch (TimeoutException e) {
      // expected
    }
  }
}
