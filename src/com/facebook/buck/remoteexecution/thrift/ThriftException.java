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

import com.facebook.thrift.TException;
import java.io.IOException;

/**
 * Convenience wrapper of the TException that inherits from IOException so the rest of the code
 * remains agnostic of the underlying data protocols we use for network operations.
 */
public class ThriftException extends IOException {
  public ThriftException(TException exception) {
    super(exception);
  }

  public ThriftException(String message) {
    super(message);
  }

  public ThriftException(String message, Throwable cause) {
    super(message, cause);
  }
}
