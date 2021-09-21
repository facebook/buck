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

package com.facebook.buck.android;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.Logger; // NOPMD

/**
 * Main entry point for executing {@link AndroidManifestReader} calls.
 *
 * <p>Expected usage: {@code this_binary <command> <android_manifest_path> <output_path}.
 */
public class AndroidManifestReaderExecutableMain {

  private static final Logger LOG =
      Logger.getLogger(AndroidManifestReaderExecutableMain.class.getName());

  public static void main(String[] args) throws IOException {
    if (args.length != 3) {
      LOG.severe("Must specify a command, a path to the Android Manifest, and an output path");
      System.exit(1);
    }

    String manifestPath = args[1];
    AndroidManifestReader androidManifestReader =
        DefaultAndroidManifestReader.forPath(Paths.get(manifestPath));

    String command = args[0];
    if (command.equals("get_package")) {
      Files.write(Paths.get(args[2]), androidManifestReader.getPackage().getBytes());
    } else {
      throw new IllegalArgumentException("Unknown command: " + command);
    }

    System.exit(0);
  }
}
