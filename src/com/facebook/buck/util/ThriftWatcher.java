/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.facebook.buck.log.Logger;

import java.io.IOException;
import java.io.InputStream;

public class ThriftWatcher {

  private static final Logger LOG = Logger.get(ThriftWatcher.class);

  private ThriftWatcher() {
  }

  /**
   * @return true if "thrift --version" can be executed successfully
   */
  public static boolean isThriftAvailable() throws InterruptedException {
    try {
      LOG.debug("Checking if Thrift is available..");
      InputStream output = new ProcessBuilder("thrift", "-version").start().getInputStream();
      byte[] bytes = new byte[7];
      output.read(bytes);
      boolean available = (new String(bytes)).equals("Thrift ");
      LOG.debug("Thrift available: %s", available);
      return available;
    } catch (IOException e) {
      return false; // Could not execute thrift.
    }
  }
}
