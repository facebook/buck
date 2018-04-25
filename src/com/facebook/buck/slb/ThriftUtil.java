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

import com.facebook.buck.log.Logger;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import org.apache.thrift.TBase;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.TSerializer;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TJSONProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.protocol.TSimpleJSONProtocol;
import org.apache.thrift.transport.TByteBuffer;
import org.apache.thrift.transport.TIOStreamTransport;
import org.apache.thrift.transport.TTransport;

public final class ThriftUtil {
  private static final Logger LOGGER = Logger.get(ThriftUtil.class);

  private ThriftUtil() {
    // Should not be instantiable.
  }

  public static TProtocolFactory getProtocolFactory(ThriftProtocol protocol) {
    // TODO(ruibm): Check whether the Factories are thread safe so we can static initialize
    // them just once.
    switch (protocol) {
      case JSON:
        return new TJSONProtocol.Factory();

      case COMPACT:
        return new TCompactProtocol.Factory();

      case BINARY:
        return new TBinaryProtocol.Factory();

      default:
        throw new IllegalArgumentException(
            String.format("Unknown ThriftProtocol [%s].", protocol.toString()));
    }
  }

  public static TProtocol newProtocolInstance(ThriftProtocol protocol, TTransport transport) {
    return getProtocolFactory(protocol).getProtocol(transport);
  }

  public static void serialize(ThriftProtocol protocol, TBase<?, ?> source, OutputStream stream)
      throws ThriftException {
    try (TTransport transport = new TIOStreamTransport(stream)) {
      TProtocol thriftProtocol = getProtocolFactory(protocol).getProtocol(transport);
      try {
        source.write(thriftProtocol);
      } catch (TException e) {
        throw new ThriftException(e);
      }
    }
  }

  public static byte[] serialize(ThriftProtocol protocol, TBase<?, ?> source)
      throws ThriftException {
    TSerializer deserializer = new TSerializer(getProtocolFactory(protocol));
    try {
      return deserializer.serialize(source);
    } catch (TException e) {
      throw new ThriftException(e);
    }
  }

  public static ByteBuffer serializeToByteBuffer(ThriftProtocol protocol, TBase<?, ?> source)
      throws ThriftException {
    TSerializer deserializer = new TSerializer(getProtocolFactory(protocol));
    try {
      return ByteBuffer.wrap(deserializer.serialize(source));
    } catch (TException e) {
      throw new ThriftException(e);
    }
  }

  public static void deserialize(ThriftProtocol protocol, byte[] source, TBase<?, ?> dest)
      throws ThriftException {
    TDeserializer deserializer = new TDeserializer(getProtocolFactory(protocol));
    dest.clear();
    try {
      deserializer.deserialize(dest, source);
    } catch (TException e) {
      throw new ThriftException(e);
    }
  }

  public static void deserialize(ThriftProtocol protocol, InputStream source, TBase<?, ?> dest)
      throws ThriftException {
    try (TIOStreamTransport responseTransport = new TIOStreamTransport(source)) {
      TProtocol responseProtocol = newProtocolInstance(protocol, responseTransport);
      dest.read(responseProtocol);
    } catch (TException e) {
      throw new ThriftException(e);
    }
  }

  /** Deserialize a message from a ByteBuffer. */
  public static void deserialize(ThriftProtocol protocol, ByteBuffer source, TBase<?, ?> dest)
      throws ThriftException {
    try (TTransport responseTransport = new TByteBuffer(source)) {
      TProtocol responseProtocol = newProtocolInstance(protocol, responseTransport);
      dest.read(responseProtocol);
    } catch (TException e) {
      throw new ThriftException(e);
    }
  }

  public static String thriftToDebugJson(TBase<?, ?> thriftObject) {
    TSerializer serializer = new TSerializer(new TSimpleJSONProtocol.Factory());
    try {
      return new String(serializer.serialize(thriftObject));
    } catch (TException e) {
      LOGGER.error(
          e,
          String.format(
              "Failed trying to serialize type [%s] to debug JSON.",
              thriftObject.getClass().getName()));
      return "FAILED_TO_DESERIALIZE";
    }
  }
}
