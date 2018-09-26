/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.remoteexecution.thrift;

import com.facebook.buck.util.function.ThrowingFunction;
import com.facebook.thrift.transport.TTransportException;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;

public class RetryingSynchronizedThriftHelperTest {

  private void testWithClientThatThrowsNTimes(
      int numThrows,
      boolean shouldSucceed,
      ThrowingFunction<DummyThriftClient, Object, Exception> testFunction) {
    DummyThriftClient throwingClient = new DummyThriftClient(numThrows);
    RetryingSynchronizedThriftHelper<DummyThriftClient> retryingClient =
        new RetryingSynchronizedThriftHelper<>(() -> throwingClient, new int[] {1, 2, 3, 4, 5});
    try {
      retryingClient.retryOnNetworkException(testFunction);
      if (!shouldSucceed) {
        Assert.fail("TTransportException was expected to be thrown.");
      }
    } catch (TTransportException | IOException e) {
      if (shouldSucceed) {
        e.printStackTrace();
        Assert.fail("Unexpected exception thrown.");
      }
    } catch (Throwable t) {
      t.printStackTrace();
      Assert.fail("Unexpected exception thrown.");
    }
  }

  @Test
  public void testRequestSucceedsOnSingleNetworkFailure() {
    testWithClientThatThrowsNTimes(1, true, client -> client.foo("123"));
  }

  @Test
  public void testRequestFailsOnManyNetworkFailures() {
    testWithClientThatThrowsNTimes(100, false, client -> client.foo("456"));
  }

  private static class DummyThriftClient {
    final int numItersToThrow;
    int numCalls = 0;

    public DummyThriftClient() {
      this(Integer.MAX_VALUE);
    }

    public DummyThriftClient(int numItersToThrow) {
      this.numItersToThrow = numItersToThrow;
    }

    public Integer foo(String s) throws TTransportException, IOException {
      if (numCalls < numItersToThrow) {
        if (numCalls++ % 2 == 0) {
          throw new TTransportException();
        } else {
          throw new IOException();
        }
      }
      return Integer.parseInt(s);
    }
  }
}
