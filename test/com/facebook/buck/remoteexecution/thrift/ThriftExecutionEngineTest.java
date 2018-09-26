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

import com.facebook.buck.remoteexecution.Protocol;
import com.facebook.buck.remoteexecution.thrift.executionengine.ThriftExecutionEngine;
import com.facebook.remoteexecution.cas.ContentAddressableStorage;
import com.facebook.remoteexecution.executionengine.ExecuteOperation;
import com.facebook.remoteexecution.executionengine.ExecuteRequest;
import com.facebook.remoteexecution.executionengine.ExecutionEngine;
import com.facebook.remoteexecution.executionengine.ExecutionEngineException;
import com.facebook.thrift.TException;
import java.io.IOException;
import java.util.Optional;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;

@SuppressWarnings("PMD.EmptyCatchBlock")
public class ThriftExecutionEngineTest {

  private final Protocol protocol = new ThriftProtocol();
  private final byte[] actionData = {0xf, 0xa, 0xc, 0xe, 0xb, 0x0, 0x0, 0xc};
  private final String traceId = "cool-id";

  @Test
  public void testTraceIdIsSentAsMetadata()
      throws IOException, InterruptedException, TException, ExecutionEngineException {
    ExecutionEngine.Iface reeClient = EasyMock.createMock(ExecutionEngine.Iface.class);
    ContentAddressableStorage.Iface casClient =
        EasyMock.createMock(ContentAddressableStorage.Iface.class);
    ThriftExecutionEngine engine =
        new ThriftExecutionEngine(reeClient, casClient, Optional.of(traceId));

    Capture<ExecuteRequest> requestCapture = EasyMock.newCapture();
    EasyMock.expect(reeClient.execute(EasyMock.capture(requestCapture)))
        .andReturn(new ExecuteOperation().setDone(true).setEx(new ExecutionEngineException()))
        .once();

    EasyMock.replay(reeClient);

    try {
      engine.execute(protocol.computeDigest(actionData));
    } catch (Throwable t) {
      // Don't care
    }

    EasyMock.verify(reeClient);

    Assert.assertTrue(requestCapture.hasCaptured());
    Assert.assertTrue(requestCapture.getValue().isSetMetadata());
    Assert.assertTrue(requestCapture.getValue().getMetadata().isSetTrace_id());
    Assert.assertEquals(traceId, requestCapture.getValue().getMetadata().getTrace_id());
  }
}
