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

package com.facebook.buck.slb;

import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;

public class ThriftUtilTest {

  @Test
  public void testSerializations() throws IOException {
    ImmutableList<Serializer> serializers =
        ImmutableList.of(new ByteSerializer(), new StreamSerializer());
    ImmutableList<Deserializer> deserializers =
        ImmutableList.of(new ByteSerializer(), new StreamSerializer());

    for (Serializer serializer : serializers) {
      for (Deserializer deserializer : deserializers) {
        for (ThriftProtocol protocol : ThriftProtocol.values()) {
          BuildJob expectedJob = createBuildJob();

          byte[] data = serializer.serialize(protocol, expectedJob);
          BuildJob actualJob = deserializer.deserialize(protocol, data);
          Assert.assertEquals(
              String.format(
                  "Serializer=[%s] Deserializer=[%s] Protocol=[%s] Expected=[%s] Actual=[%s]",
                  serializer.getClass().getName(),
                  deserializer.getClass().getName(),
                  protocol.toString(),
                  expectedJob,
                  actualJob),
              expectedJob,
              actualJob);
        }
      }
    }
  }

  private BuildJob createBuildJob() {
    BuildJob job = new BuildJob();
    job.setStatus(BuildStatus.FINISHED_SUCCESSFULLY);
    StampedeId stampedeId = new StampedeId();
    stampedeId.setId("all will be well");
    job.setStampedeId(stampedeId);
    return job;
  }

  public interface Serializer {
    byte[] serialize(ThriftProtocol protocol, BuildJob job) throws IOException;
  }

  public interface Deserializer {
    BuildJob deserialize(ThriftProtocol protocol, byte[] data) throws IOException;
  }

  public static class ByteSerializer implements Serializer, Deserializer {

    @Override
    public byte[] serialize(ThriftProtocol protocol, BuildJob job) throws ThriftException {
      return ThriftUtil.serialize(protocol, job);
    }

    @Override
    public BuildJob deserialize(ThriftProtocol protocol, byte[] data) throws IOException {
      BuildJob job = new BuildJob();
      ThriftUtil.deserialize(protocol, data, job);
      return job;
    }
  }

  public static class StreamSerializer implements Serializer, Deserializer {

    @Override
    public byte[] serialize(ThriftProtocol protocol, BuildJob job) throws IOException {
      try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
        ThriftUtil.serialize(protocol, job, stream);
        return stream.toByteArray();
      }
    }

    @Override
    public BuildJob deserialize(ThriftProtocol protocol, byte[] data) throws IOException {
      BuildJob job = new BuildJob();
      try (ByteArrayInputStream stream = new ByteArrayInputStream(data)) {
        ThriftUtil.deserialize(protocol, stream, job);
      }
      return job;
    }
  }
}
