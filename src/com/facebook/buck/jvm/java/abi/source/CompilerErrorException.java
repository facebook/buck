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

package com.facebook.buck.jvm.java.abi.source;

/**
 * Thrown when an error in source code is detected in a place that doesn't have enough context to
 * report a graceful compiler error. Caught at a higher level, where the message is reported as a
 * compiler error with relevant context.
 */
class CompilerErrorException extends Exception {
  public CompilerErrorException(String message) {
    super(message);
  }
}
