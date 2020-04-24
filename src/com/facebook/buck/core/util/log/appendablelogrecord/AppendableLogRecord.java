/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.util.log.appendablelogrecord;

import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Subclass of LogRecord that only accepts preformatted strings. LogFormatter downcasts if it
 * receives AppendableLogRecord instances, allowing us to avoid string allocations.
 */
public class AppendableLogRecord extends LogRecord {
  public AppendableLogRecord(Level level, String msg) {
    super(level, msg);
  }

  public void appendFormattedMessage(StringBuilder sb) {
    sb.append(getMessage());
  }
}
