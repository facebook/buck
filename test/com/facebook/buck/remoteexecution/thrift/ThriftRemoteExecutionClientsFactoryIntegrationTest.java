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

import static org.junit.Assert.assertEquals;

import com.facebook.remoteexecution.cas.ContentAddressableStorage;
import com.facebook.remoteexecution.cas.ContentAddressableStorageException;
import com.facebook.remoteexecution.cas.Digest;
import com.facebook.remoteexecution.cas.FindMissingBlobsRequest;
import com.facebook.remoteexecution.cas.FindMissingBlobsResponse;
import com.facebook.thrift.TException;
import com.facebook.thrift.transport.TTransportException;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

@Ignore(
    "This should only run manually as it works against a specific server and may require port forwarding.")
public class ThriftRemoteExecutionClientsFactoryIntegrationTest {

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

  private ThriftRemoteExecutionClientsFactory clientsFactory;

  @Before
  public void setUp() throws IOException, TTransportException {
    clientsFactory = new ThriftRemoteExecutionClientsFactory(host, port, casHost, casPort);
  }

  @Test
  public void testSyncCasClient() throws TException, ContentAddressableStorageException {
    ContentAddressableStorage.Iface client = clientsFactory.createCasClient();
    FindMissingBlobsRequest request = new FindMissingBlobsRequest(digests);
    FindMissingBlobsResponse response = client.findMissingBlobs(request);

    Digest missingDigest = response.missing_blob_digests.get(0);
    assertEquals(digest.hash, missingDigest.hash);
    assertEquals(digest.size_bytes, missingDigest.size_bytes);
  }
}
