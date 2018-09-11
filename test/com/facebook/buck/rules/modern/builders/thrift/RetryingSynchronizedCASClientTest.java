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

package com.facebook.buck.rules.modern.builders.thrift;

import com.facebook.buck.rules.modern.builders.thrift.ThriftRemoteExecutionClients.RetryingSynchronizedCASClient;
import com.facebook.buck.util.function.ThrowingFunction;
import com.facebook.remoteexecution.cas.BatchReadBlobsRequest;
import com.facebook.remoteexecution.cas.BatchReadBlobsResponse;
import com.facebook.remoteexecution.cas.BatchUpdateBlobsRequest;
import com.facebook.remoteexecution.cas.BatchUpdateBlobsResponse;
import com.facebook.remoteexecution.cas.ContentAddressableStorage;
import com.facebook.remoteexecution.cas.ContentAddressableStorage.Iface;
import com.facebook.remoteexecution.cas.ContentAddressableStorageException;
import com.facebook.remoteexecution.cas.FindMissingBlobsRequest;
import com.facebook.remoteexecution.cas.FindMissingBlobsResponse;
import com.facebook.remoteexecution.cas.GetTreeRequest;
import com.facebook.remoteexecution.cas.GetTreeResponse;
import com.facebook.remoteexecution.cas.ReadBlobRequest;
import com.facebook.remoteexecution.cas.ReadBlobResponse;
import com.facebook.remoteexecution.cas.UpdateBlobRequest;
import com.facebook.remoteexecution.cas.UpdateBlobResponse;
import com.facebook.thrift.TException;
import com.facebook.thrift.transport.TTransportException;
import org.junit.Assert;
import org.junit.Test;

public class RetryingSynchronizedCASClientTest {

  private void testWithClientThatThrowsNTimes(
      int numThrows,
      boolean shouldSucceed,
      ThrowingFunction<Iface, Object, Exception> functionToTest) {
    ThrowingCAS cas = new ThrowingCAS(numThrows);
    RetryingSynchronizedCASClient client = new RetryingSynchronizedCASClient(() -> cas);
    try {
      functionToTest.apply(client);
      if (!shouldSucceed) {
        Assert.fail("TTransportException was expected to be thrown.");
      }
    } catch (TTransportException e) {
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
  public void testFindMissingBlobsRequestSucceedsOnSingleNetworkFailure() {
    testWithClientThatThrowsNTimes(1, true, client -> client.findMissingBlobs(null));
  }

  @Test
  public void testFindMissingBlobsRequestFailsOnManyNetworkFailure() {
    testWithClientThatThrowsNTimes(100, false, client -> client.findMissingBlobs(null));
  }

  private static class ThrowingCAS implements ContentAddressableStorage.Iface {
    final int numItersToThrow;
    int numCalls = 0;

    public ThrowingCAS() {
      this(Integer.MAX_VALUE);
    }

    public ThrowingCAS(int numItersToThrow) {
      this.numItersToThrow = numItersToThrow;
    }

    private void coreLogic() throws TTransportException {
      if (numCalls < numItersToThrow) {
        numCalls++;
        throw new TTransportException();
      }
    }

    @Override
    public UpdateBlobResponse updateBlob(UpdateBlobRequest request)
        throws ContentAddressableStorageException, TException {
      coreLogic();
      return null;
    }

    @Override
    public BatchUpdateBlobsResponse batchUpdateBlobs(BatchUpdateBlobsRequest request)
        throws ContentAddressableStorageException, TException {
      coreLogic();
      return null;
    }

    @Override
    public ReadBlobResponse readBlob(ReadBlobRequest request)
        throws ContentAddressableStorageException, TException {
      coreLogic();
      return null;
    }

    @Override
    public BatchReadBlobsResponse batchReadBlobs(BatchReadBlobsRequest request)
        throws ContentAddressableStorageException, TException {
      coreLogic();
      return null;
    }

    @Override
    public FindMissingBlobsResponse findMissingBlobs(FindMissingBlobsRequest request)
        throws ContentAddressableStorageException, TException {
      coreLogic();
      return null;
    }

    @Override
    public GetTreeResponse getTree(GetTreeRequest request)
        throws ContentAddressableStorageException, TException {
      coreLogic();
      return null;
    }
  }
}
