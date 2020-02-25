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

package com.facebook.buck.logd.server;

import java.io.IOException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** This class is run by logd.pex to start up LogD server */
public class LogdServerMain {
  private static final Logger LOG = LogManager.getLogger();
  private static final int PORT = 8980;

  /**
   * Main class starting LogD server
   *
   * @param args
   */
  public static void main(String[] args) {
    LogdServer logdServer = new LogdServer(PORT);
    try {
      logdServer.start();
      LOG.debug("Starting up LogD...");
    } catch (IOException e) {
      LOG.error("Failed to start LogD", e);
    }
  }
}
