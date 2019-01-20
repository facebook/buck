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

package com.facebook.buck.eden;

import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.util.timing.SettableFakeClock;
import com.facebook.eden.thrift.EdenError;
import com.facebook.eden.thrift.EdenService;
import com.facebook.eden.thrift.SHA1Result;
import com.facebook.thrift.TException;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.sun.jna.LastErrorException;
import java.io.IOException;
import java.util.List;
import org.easymock.EasyMockSupport;
import org.junit.Test;

public class ReconnectingEdenClientTest extends EasyMockSupport {
  @Test
  public void requestToAStaleClientShouldBeRetriedWithAFreshClient()
      throws EdenError, IOException, TException {
    String mountPoint = "/some/mountPoint";
    List<String> paths = ImmutableList.of(".buckconfig");
    HashCode hash = HashCode.fromString("2b8b815229aa8a61e483fb4ba0588b8b6c491890");
    SHA1Result sha1Result = new SHA1Result();
    sha1Result.setSha1(hash.asBytes());
    long currentTime = 1000L;
    SettableFakeClock clock = new SettableFakeClock(currentTime, 0);

    // Stale client that throws a TException that has a LastErrorException as a cause.
    EdenService.Client staleClient = createMock(EdenService.Client.class);
    TException exceptionBackedByLastErrorException =
        new TException(new LastErrorException("Broken pipe"));
    expect(staleClient.getSHA1(mountPoint, paths)).andThrow(exceptionBackedByLastErrorException);

    // A connected client that should succeed.
    EdenService.Client connectedClient = createMock(EdenService.Client.class);
    expect(connectedClient.getSHA1(mountPoint, paths))
        .andReturn(ImmutableList.of(sha1Result))
        .times(2);

    // A connected client that should succeed.
    EdenService.Client secondConnectedClient = createMock(EdenService.Client.class);
    expect(secondConnectedClient.getSHA1(mountPoint, paths))
        .andReturn(ImmutableList.of(sha1Result));

    // The calls to client.getSHA1() should trigger the following behavior:
    // - First call to client.getSHA1() should call createNewThriftClient() twice:
    //   - First, it should return a client that is demonstrably stale because of the TException it
    //     throws.
    //   - Next, it should return a fresh client to replace the stale one.
    // - Second call to client.getSHA1() should reuse the existing Thrift client.
    // - Third call to client.getSHA1() should request a new Thrift client because its existing
    //   Thrift client has not been used within the idle time threshold.
    ReconnectingEdenClient.ThriftClientFactory thriftClientFactory =
        createMock(ReconnectingEdenClient.ThriftClientFactory.class);
    expect(thriftClientFactory.createNewThriftClient()).andReturn(staleClient);
    expect(thriftClientFactory.createNewThriftClient()).andReturn(connectedClient);
    expect(thriftClientFactory.createNewThriftClient()).andReturn(secondConnectedClient);
    replayAll();

    ReconnectingEdenClient client = new ReconnectingEdenClient(thriftClientFactory, clock);
    List<SHA1Result> sha1s_1 = client.getSHA1(mountPoint, paths);
    assertEquals(ImmutableList.of(sha1Result), sha1s_1);

    // Use the client 1 millisecond before the idle time threshold kicks in.
    currentTime += ReconnectingEdenClient.IDLE_TIME_THRESHOLD_IN_MILLIS - 1;
    clock.setCurrentTimeMillis(currentTime);
    List<SHA1Result> sha1s_2 = client.getSHA1(mountPoint, paths);
    assertEquals(ImmutableList.of(sha1Result), sha1s_2);

    // Use the client when the idle time threshold kicks in.
    currentTime += ReconnectingEdenClient.IDLE_TIME_THRESHOLD_IN_MILLIS;
    clock.setCurrentTimeMillis(currentTime);
    List<SHA1Result> sha1s_3 = client.getSHA1(mountPoint, paths);
    assertEquals(ImmutableList.of(sha1Result), sha1s_3);

    verifyAll();
  }
}
