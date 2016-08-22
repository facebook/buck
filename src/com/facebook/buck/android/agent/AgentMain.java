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

package com.facebook.buck.android.agent;

import com.facebook.buck.android.agent.util.AgentUtil;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main class for an agent that runs on an Android device to aid app installation.
 *
 * <p>This does not run as a normal Android app.  It is packaged into an APK and installed
 * as a convenient way to get it on the device, but it is run from the "adb shell" command line
 * using the "dalvikvm" command.  Therefore, we do not have an Android Context and therefore
 * cannot interact with any system services.
 */
public class AgentMain {

  private AgentMain() {}

  private static final Logger LOG = Logger.getLogger(AgentMain.class.getName());

  public static void main(String args[]) throws IOException {
    if (args.length == 0) {
      LOG.severe("No command specified");
      System.exit(1);
    }

    String command = args[0];
    List<String> userArgs =
        Collections.unmodifiableList(Arrays.asList(args).subList(1, args.length));

    try {
      if (command.equals("get-signature")) {
        doGetSignature(userArgs);
      } else if (command.equals("mkdir-p")) {
        doMkdirP(userArgs);
      } else {
        throw new IllegalArgumentException("Unknown command: " + command);
      }
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Command failed", e);
      System.exit(1);
    }
    System.exit(0);
  }

  /**
   * Print the signature of an APK to stdout.  The APK path is passed as
   * the only command line argument.
   */
  private static void doGetSignature(List<String> userArgs) throws IOException {
    if (userArgs.size() != 1) {
      throw new IllegalArgumentException("usage: get-signature FILE");
    }
    String packagePath = userArgs.get(0);

    System.out.println(AgentUtil.getJarSignature(packagePath));
  }

  /**
   * Roughly equivalent to the shell command "mkdir -p".
   *
   * Note that some (all?) versions of Android will force restrictive permissions
   * on the created directories.
   */
  private static void doMkdirP(List<String> userArgs) throws IOException {
    if (userArgs.size() != 1) {
      throw new IllegalArgumentException("usage: mkdir -p PATH");
    }

    File path = new File(userArgs.get(0));
    boolean success = path.mkdirs();
    if (!success) {
      throw new IOException("Creating directory failed.");
    }
  }
}
