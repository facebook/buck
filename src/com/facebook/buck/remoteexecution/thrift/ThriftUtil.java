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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.remoteexecution.cas.ContentAddressableStorageException;
import com.facebook.thrift.TBase;
import com.facebook.thrift.TException;
import com.facebook.thrift.TSerializer;
import com.facebook.thrift.protocol.TCompactJSONProtocol;
import com.facebook.thrift.transport.TTransportException;

/** Convenience methods to handle fbthrift. */
public final class ThriftUtil {
  private static final Logger LOGGER = Logger.get(ThriftUtil.class);

  private ThriftUtil() {
    // Should not be instantiable.
  }

  /** Prints a JSON readable version of the argument thrift object. */
  public static String thriftToDebugJson(TBase thriftObject) {
    TSerializer serializer = new TSerializer(new TCompactJSONProtocol.Factory());
    try {
      return String.format(
          "%s%s",
          thriftObject.getClass().getSimpleName(), new String(serializer.serialize(thriftObject)));
    } catch (TException e) {
      LOGGER.error(
          e,
          String.format(
              "Failed trying to serialize type [%s] to debug JSON.",
              thriftObject.getClass().getName()));
      return "FAILED_TO_DESERIALIZE";
    }
  }

  /**
   * Gets extra details about any thrift related exception or absent if not such details are
   * available.
   */
  public static String getExceptionDetails(Exception exception) {
    if (exception instanceof TTransportException) {
      TTransportException transportException = (TTransportException) exception;
      return String.format(
          "Encountered TTransport exception of type [%s].",
          getTTransportExceptionType(transportException));
    } else if (exception instanceof ContentAddressableStorageException) {
      ContentAddressableStorageException casException =
          (ContentAddressableStorageException) exception;
      return String.format(
          "CAS Exception with contents: [%s].", ThriftUtil.thriftToDebugJson(casException));
    } else {
      return exception.toString();
    }
  }

  /** A debug string representation of the TTransportException.type file. */
  public static String getTTransportExceptionType(TTransportException exception) {
    String typeString;
    switch (exception.getType()) {
      case TTransportException.UNKNOWN:
        typeString = "UNKNOWN";
        break;
      case TTransportException.NOT_OPEN:
        typeString = "NOT_OPEN";
        break;
      case TTransportException.ALREADY_OPEN:
        typeString = "ALREADY_OPEN";
        break;
      case TTransportException.TIMED_OUT:
        typeString = "TIMED_OUT";
        break;
      case TTransportException.END_OF_FILE:
        typeString = "END_OF_FILE";
        break;
      default:
        typeString = Integer.toString(exception.getType());
        break;
    }

    return String.format("%d:%s", exception.getType(), typeString);
  }
}
