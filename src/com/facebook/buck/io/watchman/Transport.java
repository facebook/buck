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

package com.facebook.buck.io.watchman;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * An abstraction for IPC via messages. Messages are sent via an output stream ({@link
 * #getOutputStream()}) and received via an input stream ({@link #getInputStream()})
 */
public interface Transport extends Closeable {
  /**
   * Returns an input stream for reading messages
   *
   * @return an input stream for reading messages in this IPC
   */
  InputStream getInputStream();

  /**
   * Returns an input stream for writing messages.
   *
   * @return an input stream for writing messages in this IPC
   */
  OutputStream getOutputStream();
}
