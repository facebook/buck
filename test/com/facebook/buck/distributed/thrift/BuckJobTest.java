/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.distributed.thrift;

import static org.junit.Assert.assertEquals;

import org.apache.thrift.TDeserializer;
import org.apache.thrift.TSerializer;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.junit.Test;

public class BuckJobTest {
  private static final String JOB_ID = "ID_1";
  private static final long JOB_TS = 123L;

  @Test
  public void testSerializeDeserialize() throws Exception {
    BuckJob jobToSerialize = new BuckJob();
    jobToSerialize.setId(JOB_ID);
    jobToSerialize.setTimestamp_ms(JOB_TS);

    TSerializer serializer = new TSerializer(new TBinaryProtocol.Factory());
    byte[] serializedBytes = serializer.serialize(jobToSerialize);

    TDeserializer deserializer = new TDeserializer(new TBinaryProtocol.Factory());
    BuckJob deserializedJob = new BuckJob();
    deserializer.deserialize(deserializedJob, serializedBytes);

    assertEquals(JOB_ID, deserializedJob.getId());
    assertEquals(JOB_TS, deserializedJob.getTimestamp_ms());
  }
}
