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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.thrift.TBase;
import com.facebook.thrift.TException;
import com.facebook.thrift.TSerializer;
import com.facebook.thrift.protocol.TCompactJSONProtocol;

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
