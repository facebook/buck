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

import com.facebook.remoteexecution.cas.ContentAddressableStorageException;
import com.facebook.remoteexecution.cas.GetTreeRequest;
import com.facebook.thrift.transport.TTransportException;
import org.junit.Assert;
import org.junit.Test;

public class ThriftUtilTest {
  @Test
  public void testDebugJsonForObjectWithFields() {
    GetTreeRequest request = new GetTreeRequest();
    String token = "cowabunga";
    request.setPage_token(token);
    String msg = ThriftUtil.thriftToDebugJson(request);
    Assert.assertTrue(msg.contains(token));
  }

  @Test
  public void testExtraDetailsForCasException() {
    ContentAddressableStorageException exception = new ContentAddressableStorageException();
    String msg = "smash";
    exception.setMessage(msg);
    String details = ThriftUtil.getExceptionDetails(exception);
    Assert.assertTrue(details, details.contains(msg));
  }

  @Test
  public void testExtraDetailsForTTransportException() {
    TTransportException exception = new TTransportException(TTransportException.END_OF_FILE);
    String details = ThriftUtil.getExceptionDetails(exception);
    Assert.assertTrue(details, details.contains("END_OF_FILE"));
  }

  @Test
  public void testExtraDetailsWithRuntimeException() {
    String msg = "topspin";
    String details = ThriftUtil.getExceptionDetails(new RuntimeException(msg));
    Assert.assertTrue(details, details.contains(msg));
  }
}
