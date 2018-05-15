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
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ServerContentsProviderTest {

  private static long FUTURE_TIMEOUT_SECONDS = 1;

  private static final String HASH1 = "abcd";
  private static final String HASH2 = "xkcd";
  private static final String HASH3 = "buck";
  private static final String HASH4 = "face";
  private static final String HASH5 = "book";

  private static final String FILE_CONTENTS1 = "my";
  private static final String FILE_CONTENTS2 = "super";
  private static final String FILE_CONTENTS3 = "cool";
  private static final String FILE_CONTENTS4 = "contents";

  private Path path1, path2, path3, path4, path5;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private DistBuildService distBuildService;
  private ServerContentsProvider provider;
  private FakeExecutor fakeScheduledExecutor;
  private FileMaterializationStatsTracker statsTracker;

  @Before
  public void setUp() {
    distBuildService = EasyMock.createMock(DistBuildService.class);
    fakeScheduledExecutor = new FakeExecutor();
    statsTracker = EasyMock.createStrictMock(FileMaterializationStatsTracker.class);
    path1 = tmp.getRoot().resolve("file1");
    path2 = tmp.getRoot().resolve("file2");
    path3 = tmp.getRoot().resolve("file3");
    path4 = tmp.getRoot().resolve("file4");
    path5 = tmp.getRoot().resolve("file5");
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
            distBuildService,
            fakeScheduledExecutor,
            MoreExecutors.newDirectExecutorService(),
            statsTracker,
            bufferPeriodMs,
            bufferMaxSize);
  }

  @Test
  public void testMultiFetchPeriodWorks()
      throws IOException, InterruptedException, ExecutionException, TimeoutException {
    initProvider(1, 100);

    ImmutableMap.Builder<String, byte[]> result1 = new ImmutableMap.Builder<>();
    result1.put(HASH1, FILE_CONTENTS1.getBytes(StandardCharsets.UTF_8));
    result1.put(HASH2, FILE_CONTENTS2.getBytes(StandardCharsets.UTF_8));
    expect(distBuildService.multiFetchSourceFiles(ImmutableSet.of(HASH1, HASH2)))
        .andReturn(result1.build())
        .once();
    statsTracker.recordPeriodicCasMultiFetch(EasyMock.anyLong());
    expectLastCall().once();

    ImmutableMap.Builder<String, byte[]> result2 = new ImmutableMap.Builder<>();
    result2.put(HASH3, FILE_CONTENTS3.getBytes(StandardCharsets.UTF_8));
    expect(distBuildService.multiFetchSourceFiles(ImmutableSet.of(HASH3)))
        .andReturn(result2.build())
        .once();
    statsTracker.recordPeriodicCasMultiFetch(EasyMock.anyLong());
    expectLastCall().once();
    replay(distBuildService);
    replay(statsTracker);

    Future<?> future1, future2, future3;
    future1 =
        provider.materializeFileContentsAsync(
            new BuildJobStateFileHashEntry().setSha1(HASH1), path1);
    future2 =
        provider.materializeFileContentsAsync(
            new BuildJobStateFileHashEntry().setSha1(HASH2), path2);
    fakeScheduledExecutor.drain();
    future1.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    future2.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

    future3 =
        provider.materializeFileContentsAsync(
            new BuildJobStateFileHashEntry().setSha1(HASH3), path3);
    fakeScheduledExecutor.drain();
    future3.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

    // Extra run to check for calls with zero HashCodes.
    fakeScheduledExecutor.drain();

    verify(distBuildService);
    verify(statsTracker);
    Assert.assertArrayEquals(
        FILE_CONTENTS1.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(path1));
    Assert.assertArrayEquals(
        FILE_CONTENTS2.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(path2));
    Assert.assertArrayEquals(
        FILE_CONTENTS3.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(path3));
  }

  @Test
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void testFutureIsSetOnRemoteCallException()
      throws IOException, InterruptedException, ExecutionException, TimeoutException {
    initProvider(1, 100);

    ImmutableMap.Builder<String, byte[]> result1 = new ImmutableMap.Builder<>();
    result1.put(HASH1, FILE_CONTENTS1.getBytes(StandardCharsets.UTF_8));
    expect(distBuildService.multiFetchSourceFiles(ImmutableSet.of(HASH1)))
        .andThrow(new IOException("remote call failed"));

    replay(distBuildService);

    Future<?> future1 =
        provider.materializeFileContentsAsync(
            new BuildJobStateFileHashEntry().setSha1(HASH1), path1);

    fakeScheduledExecutor.drain();

    try {
      future1.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      Assert.fail("ExecutionException was expected.");
    } catch (ExecutionException ex) {
      Assert.assertTrue(ex.getCause() instanceof IOException);
    }

    verify(distBuildService);
  }

  @Test
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void testMultiFetchMaxBufferSizeWorks()
      throws IOException, InterruptedException, ExecutionException, TimeoutException {
    initProvider(1000 * 60 * 60 * 24, 2);

    // We should get request for 2 files first.
    ImmutableMap.Builder<String, byte[]> result1 = new ImmutableMap.Builder<>();
    result1.put(HASH1, FILE_CONTENTS1.getBytes(StandardCharsets.UTF_8));
    result1.put(HASH2, FILE_CONTENTS2.getBytes(StandardCharsets.UTF_8));
    expect(distBuildService.multiFetchSourceFiles(ImmutableSet.of(HASH1, HASH2)))
        .andReturn(result1.build())
        .once();
    statsTracker.recordFullBufferCasMultiFetch(EasyMock.anyLong());
    expectLastCall().once();

    // Then 2 again.
    ImmutableMap.Builder<String, byte[]> result2 = new ImmutableMap.Builder<>();
    result2.put(HASH3, FILE_CONTENTS3.getBytes(StandardCharsets.UTF_8));
    result2.put(HASH4, FILE_CONTENTS4.getBytes(StandardCharsets.UTF_8));
    expect(distBuildService.multiFetchSourceFiles(ImmutableSet.of(HASH3, HASH4)))
        .andReturn(result2.build())
        .once();
    statsTracker.recordFullBufferCasMultiFetch(EasyMock.anyLong());
    expectLastCall().once();

    // One lone request (for HASH5) should never be fetched.
    replay(distBuildService);
    replay(statsTracker);

    Future<?> future1, future2, future3, future4, future5;
    future1 =
        provider.materializeFileContentsAsync(
            new BuildJobStateFileHashEntry().setSha1(HASH1), path1);
    future2 =
        provider.materializeFileContentsAsync(
            new BuildJobStateFileHashEntry().setSha1(HASH2), path2);
    future3 =
        provider.materializeFileContentsAsync(
            new BuildJobStateFileHashEntry().setSha1(HASH3), path3);
    future4 =
        provider.materializeFileContentsAsync(
            new BuildJobStateFileHashEntry().setSha1(HASH4), path4);
    future5 =
        provider.materializeFileContentsAsync(
            new BuildJobStateFileHashEntry().setSha1(HASH5), path5);
    // We should not need to drain the scheduler.
    // Scheduler is only supposed to be used for periodic cleanup.
    future1.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    future2.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    future3.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    future4.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

    verify(distBuildService);
    verify(statsTracker);
    Assert.assertArrayEquals(
        FILE_CONTENTS1.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(path1));
    Assert.assertArrayEquals(
        FILE_CONTENTS2.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(path2));
    Assert.assertArrayEquals(
        FILE_CONTENTS3.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(path3));
    Assert.assertArrayEquals(
        FILE_CONTENTS4.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(path4));

    try {
      future5.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      Assert.fail("Timeout was expected.");
    } catch (TimeoutException e) {
      // expected
    }
  }
}
