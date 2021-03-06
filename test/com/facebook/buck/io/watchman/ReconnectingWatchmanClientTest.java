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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableMap;
import org.easymock.EasyMock;
import org.junit.Test;

public class ReconnectingWatchmanClientTest {

  private ReconnectingWatchmanClient.UnderlyingWatchmanOpener underlyingWatchmanOpenerMock =
      EasyMock.createStrictMock(ReconnectingWatchmanClient.UnderlyingWatchmanOpener.class);

  private ReconnectingWatchmanClient reconnectingWatchmanClient =
      new ReconnectingWatchmanClient(underlyingWatchmanOpenerMock);

  @Test
  public void worksFineWhenNoErrors() throws Exception {
    WatchmanClient mock = EasyMock.createStrictMock(WatchmanClient.class);
    EasyMock.expect(mock.queryWithTimeout(17, 19, WatchmanQuery.getPid()))
        .andReturn(Either.ofLeft(ImmutableMap.of("a", "b")));
    EasyMock.expect(mock.queryWithTimeout(23, 29, WatchmanQuery.watchProject("/x")))
        .andReturn(Either.ofLeft(ImmutableMap.of("c", "d")));
    mock.close();

    EasyMock.expect(underlyingWatchmanOpenerMock.open()).andReturn(mock);

    EasyMock.replay(mock);
    EasyMock.replay(underlyingWatchmanOpenerMock);

    assertEquals(
        Either.ofLeft(ImmutableMap.of("a", "b")),
        reconnectingWatchmanClient.queryWithTimeout(17, 19, WatchmanQuery.getPid()));
    assertNotNull(reconnectingWatchmanClient.getUnderlying());
    assertEquals(
        Either.ofLeft(ImmutableMap.of("c", "d")),
        reconnectingWatchmanClient.queryWithTimeout(23, 29, WatchmanQuery.watchProject("/x")));
    assertNotNull(reconnectingWatchmanClient.getUnderlying());
    reconnectingWatchmanClient.close();
    assertNull(reconnectingWatchmanClient.getUnderlying());

    EasyMock.verify(mock);
    EasyMock.verify(underlyingWatchmanOpenerMock);
  }

  @Test
  public void reconnectOnTimeout() throws Exception {
    WatchmanClient mock = EasyMock.createStrictMock(WatchmanClient.class);
    EasyMock.expect(mock.queryWithTimeout(17, 19, WatchmanQuery.getPid()))
        .andReturn(Either.ofRight(WatchmanClient.Timeout.INSTANCE));
    mock.close();

    EasyMock.expect(underlyingWatchmanOpenerMock.open()).andReturn(mock);

    EasyMock.replay(mock);
    EasyMock.replay(underlyingWatchmanOpenerMock);

    assertEquals(
        Either.ofRight(WatchmanClient.Timeout.INSTANCE),
        reconnectingWatchmanClient.queryWithTimeout(17, 19, WatchmanQuery.getPid()));
    assertNull(reconnectingWatchmanClient.getUnderlying());

    EasyMock.verify(mock);
    EasyMock.verify(underlyingWatchmanOpenerMock);
  }

  @Test
  public void reconnectOnError() throws Exception {
    WatchmanClient mock = EasyMock.createStrictMock(WatchmanClient.class);
    EasyMock.expect(mock.queryWithTimeout(17, 19, WatchmanQuery.getPid()))
        .andThrow(new RuntimeException("test"));
    mock.close();
    EasyMock.expect(underlyingWatchmanOpenerMock.open()).andReturn(mock);

    EasyMock.replay(mock);
    EasyMock.replay(underlyingWatchmanOpenerMock);

    try {
      reconnectingWatchmanClient.queryWithTimeout(17, 19, WatchmanQuery.getPid());
      fail();
    } catch (RuntimeException e) {
      // expected
    }
    assertNull(reconnectingWatchmanClient.getUnderlying());

    EasyMock.verify(mock);
    EasyMock.verify(underlyingWatchmanOpenerMock);
  }
}
