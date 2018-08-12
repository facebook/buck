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

package com.facebook.buck.rules.modern.builders.thrift.cas;

import com.facebook.buck.rules.modern.builders.AsyncBlobFetcher;
import com.facebook.buck.rules.modern.builders.Protocol;
import com.facebook.buck.rules.modern.builders.thrift.ThriftProtocol;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.remoteexecution.cas.ContentAddressableStorage;
import com.facebook.remoteexecution.cas.ReadBlobRequest;
import com.facebook.remoteexecution.cas.ReadBlobResponse;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;

/** A Thrift-based implementation of fetching outputs from the CAS. */
public class ThriftAsyncBlobFetcher implements AsyncBlobFetcher {

  private final ContentAddressableStorage.AsyncClient client;

  public ThriftAsyncBlobFetcher(ContentAddressableStorage.AsyncClient client) {
    this.client = client;
  }

  @Override
  public ListenableFuture<ByteBuffer> fetch(Protocol.Digest digest) {
    ReadBlobRequest request = new ReadBlobRequest(ThriftProtocol.get(digest));
    SettableFuture<ByteBuffer> future = SettableFuture.create();

    try {
      client.readBlob(
          request,
          new AsyncMethodCallback<ReadBlobResponse>() {
            @Override
            public void onComplete(ReadBlobResponse response) {
              future.set(response.data);
            }

            @Override
            public void onError(Exception exception) {
              future.setException(exception);
            }
          });
    } catch (TException e) {
      throw new BuckUncheckedExecutionException(e);
    }

    return future;
  }

  @Override
  public void fetchToStream(Protocol.Digest digest, OutputStream outputStream) {
    // TODO(orr): Not implementing since GrpcRemoteExecution doesn't implement this as well. If so,
    // should we remote this option from the API? Otherwise we should implement.
    throw new UnsupportedOperationException("Not implemented");
  }
}
