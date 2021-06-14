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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.easymock.EasyMock;
import org.junit.Test;

public class PooledWatchmanClientTest {
  @Test
  public void simpleSingleThreaded() throws Exception {
    PooledWatchmanClient.UnderlyingWatchmanOpener opener =
        EasyMock.createStrictMock(PooledWatchmanClient.UnderlyingWatchmanOpener.class);

    WatchmanClient client1 = EasyMock.createStrictMock(WatchmanClient.class);

    EasyMock.expect(opener.open()).andReturn(client1);

    EasyMock.expect(client1.queryWithTimeout(10, 20, WatchmanQuery.getPid()))
        .andReturn(Either.ofLeft(ImmutableMap.of()));
    EasyMock.expect(client1.queryWithTimeout(30, 40, WatchmanQuery.watch("/")))
        .andReturn(Either.ofLeft(ImmutableMap.of()));
    client1.close();

    EasyMock.replay(opener, client1);

    PooledWatchmanClient pool = new PooledWatchmanClient(opener);

    assertEquals(
        Either.ofLeft(ImmutableMap.of()), pool.queryWithTimeout(10, 20, WatchmanQuery.getPid()));
    assertEquals(
        Either.ofLeft(ImmutableMap.of()), pool.queryWithTimeout(30, 40, WatchmanQuery.watch("/")));
    pool.closePool();

    EasyMock.verify(opener, client1);
  }

  @Test
  public void simpleMultiThreaded() throws Exception {
    PooledWatchmanClient.UnderlyingWatchmanOpener opener =
        EasyMock.createStrictMock(PooledWatchmanClient.UnderlyingWatchmanOpener.class);

    CountDownLatch bothRequestsExecute = new CountDownLatch(2);
    CountDownLatch firstRequestStarted = new CountDownLatch(1);

    // Not using easymock, because it does not allow blocking in callbacks

    class Client1 implements WatchmanClient {
      private final AtomicBoolean closed = new AtomicBoolean();

      @Override
      public Either<ImmutableMap<String, Object>, Timeout> queryWithTimeout(
          long timeoutNanos, long warnTimeNanos, WatchmanQuery query)
          throws IOException, InterruptedException {
        assertFalse(closed.get());

        firstRequestStarted.countDown();

        bothRequestsExecute.countDown();
        bothRequestsExecute.await();
        return Either.ofLeft(ImmutableMap.of());
      }

      @Override
      public void close() throws IOException {
        assertTrue(closed.compareAndSet(false, true));
      }
    }

    class Client2 implements WatchmanClient {
      private final AtomicBoolean closed = new AtomicBoolean();

      @Override
      public Either<ImmutableMap<String, Object>, Timeout> queryWithTimeout(
          long timeoutNanos, long warnTimeNanos, WatchmanQuery query)
          throws IOException, InterruptedException {
        assertFalse(closed.get());

        bothRequestsExecute.countDown();
        bothRequestsExecute.await();

        return Either.ofLeft(ImmutableMap.of());
      }

      @Override
      public void close() throws IOException {
        assertTrue(closed.compareAndSet(false, true));
      }
    }

    Client1 client1 = new Client1();
    Client2 client2 = new Client2();

    EasyMock.expect(opener.open()).andReturn(client1);
    EasyMock.expect(opener.open()).andReturn(client2);

    EasyMock.replay(opener);

    PooledWatchmanClient pool = new PooledWatchmanClient(opener);

    ExecutorService executor = Executors.newFixedThreadPool(10);
    Future<Either<ImmutableMap<String, Object>, WatchmanClient.Timeout>> f1 =
        executor.submit(() -> pool.queryWithTimeout(10, 20, WatchmanQuery.getPid()));
    Future<Either<ImmutableMap<String, Object>, WatchmanClient.Timeout>> f2 =
        executor.submit(() -> pool.queryWithTimeout(30, 40, WatchmanQuery.watch("/")));

    assertEquals(Either.ofLeft(ImmutableMap.of()), f1.get());
    firstRequestStarted.await();
    assertEquals(Either.ofLeft(ImmutableMap.of()), f2.get());

    pool.closePool();

    EasyMock.verify(opener);

    assertTrue(client1.closed.get());
    assertTrue(client2.closed.get());
  }
}
