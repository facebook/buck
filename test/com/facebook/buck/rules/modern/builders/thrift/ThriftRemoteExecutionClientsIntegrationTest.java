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

import static org.junit.Assert.assertEquals;

import com.facebook.remoteexecution.cas.ContentAddressableStorage;
import com.facebook.remoteexecution.cas.ContentAddressableStorage.AsyncClient.findMissingBlobs_call;
import com.facebook.remoteexecution.cas.ContentAddressableStorageException;
import com.facebook.remoteexecution.cas.Digest;
import com.facebook.remoteexecution.cas.FindMissingBlobsRequest;
import com.facebook.remoteexecution.cas.FindMissingBlobsResponse;
import com.facebook.thrift.TException;
import com.facebook.thrift.async.TAsyncMethodCall;
import com.facebook.thrift.transport.TTransportException;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

@Ignore(
    "This should only run manually as it works against a specific server and may require port forwarding.")
public class ThriftRemoteExecutionClientsIntegrationTest {

  // If you want to run this from IntelliJ running locally, you may need to run ssh port
  // forwarding to your server:
  //
  // HOST=<your_server>
  // LOCAL_PORT=9001
  // REMOTE_PORT=9000  # CAS --server-port running on your server
  //
  // $ ssh -L $LOCAL_PORT:$HOST:$REMOTE_PORT -N $HOST

  private static final String host = "localhost";
  private static final int port = 9003;
  private static final String casHost = "localhost";
  private static final int casPort = 9002;
  private static final Digest digest = new Digest("missing-hash", 123);
  private static final List<Digest> digests = Collections.singletonList(digest);

  private ThriftRemoteExecutionClients clients;

  @Before
  public void setUp() throws IOException, TTransportException {
    clients = new ThriftRemoteExecutionClients(host, port, casHost, casPort);
  }

  @Test
  public void testSyncCasClient() throws TException, ContentAddressableStorageException {
    ContentAddressableStorage.Client client = clients.createCasClient();
    FindMissingBlobsRequest request = new FindMissingBlobsRequest(digests);
    FindMissingBlobsResponse response = client.findMissingBlobs(request);

    Digest missingDigest = response.missing_blob_digests.get(0);
    assertEquals(digest.hash, missingDigest.hash);
    assertEquals(digest.size_bytes, missingDigest.size_bytes);
  }

  @Test
  public void testAsyncCasClient() throws TException, InterruptedException, IOException {
    ContentAddressableStorage.AsyncClient asyncClient =
        clients.createAsyncCasClientFactory().getAsyncClient();
    FindMissingBlobsRequest request = new FindMissingBlobsRequest(digests);

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<FindMissingBlobsResponse> response = new AtomicReference<>();

    asyncClient.findMissingBlobs(
        request,
        new com.facebook.thrift.async.AsyncMethodCallback() {
          @Override
          public void onComplete(TAsyncMethodCall tAsyncMethodCall) {
            if (tAsyncMethodCall instanceof findMissingBlobs_call) {
              FindMissingBlobsResponse r = null;
              try {
                r = ((findMissingBlobs_call) tAsyncMethodCall).getResult();
              } catch (TException | ContentAddressableStorageException e) {
                onError(e);
              }
              response.set(r);
              latch.countDown();
            } else {
              throw new RuntimeException("Method callback type wasn't findMissingBlobs_call");
            }
          }

          @Override
          public void onError(Exception e) {
            throw new RuntimeException(e);
          }
        });

    latch.await(1, TimeUnit.SECONDS);
    Digest missingDigest = response.get().missing_blob_digests.get(0);
    assertEquals(digest.hash, missingDigest.hash);
    assertEquals(digest.size_bytes, missingDigest.size_bytes);
  }
}
