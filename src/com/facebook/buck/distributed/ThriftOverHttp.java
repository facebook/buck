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

import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TJSONProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.THttpClient;
import org.apache.thrift.transport.TTransportException;

public class ThriftOverHttp implements AutoCloseable {
  private static final int READ_TIMEOUT_MS = 50000;
  private THttpClient transport;
  private TProtocol proto;

  public enum Encoding {
    json,
    binary,
    compact
  }

  public ThriftOverHttp(String uri, Encoding type) throws TTransportException {
    transport = new THttpClient(uri);
    transport.setReadTimeout(READ_TIMEOUT_MS);
    transport.setCustomHeader("X-Thrift-Protocol", type.toString());
    transport.open();
    switch (type) {
      case json:
        proto = new TJSONProtocol(transport);
        break;
      case compact:
        proto = new TCompactProtocol(transport);
        break;
      case binary:
      default:
        proto = new TBinaryProtocol(transport);
        break;
    }
  }

  public void writeAndSend(TBase<?, ?> struct) throws TException {
    this.write(struct);
    this.flush();
  }

  public void write(TBase<?, ?> struct) throws TException {
    struct.write(proto);
  }

  public void flush() throws TTransportException {
    transport.flush();
  }

  public void read(TBase<?, ?> struct) throws TException {
    struct.read(proto);
  }

  @Override
  public void close() throws Exception {
    transport.close();
  }
}
