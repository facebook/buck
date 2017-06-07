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

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.slb.ThriftProtocol;
import com.facebook.buck.slb.ThriftUtil;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public abstract class BuildJobStateSerializer {
  private static final ThriftProtocol PROTOCOL = ThriftProtocol.COMPACT;

  private BuildJobStateSerializer() {
    // Do not instantiate.
  }

  public static void serialize(BuildJobState state, OutputStream stream) throws IOException {
    try (DeflaterOutputStream zlibStream = new DeflaterOutputStream(stream)) {
      ThriftUtil.serialize(PROTOCOL, state, zlibStream);
    }
  }

  public static byte[] serialize(BuildJobState state) throws IOException {
    try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
      serialize(state, stream);
      return stream.toByteArray();
    }
  }

  public static BuildJobState deserialize(InputStream stream) throws IOException {
    BuildJobState state = new BuildJobState();
    try (InflaterInputStream zlibStream = new InflaterInputStream(stream)) {
      ThriftUtil.deserialize(PROTOCOL, zlibStream, state);
    }

    return state;
  }

  public static BuildJobState deserialize(byte[] data) throws IOException {
    try (ByteArrayInputStream stream = new ByteArrayInputStream(data)) {
      return deserialize(stream);
    }
  }
}
