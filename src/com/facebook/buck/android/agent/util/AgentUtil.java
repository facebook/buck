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

package com.facebook.buck.android.agent.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Non-instantiable class for holding functionality that runs both on the host
 * and in the android agent.
 */
public final class AgentUtil {
  private AgentUtil() {}

  // These must match the values in the agent manifest.
  public static final String AGENT_PACKAGE_NAME = "com.facebook.buck.android.agent";
  public static final String AGENT_VERSION_CODE = "2";

  /**
   * Size in bytes of the binary data use to generate the secret key for receive-file.
   */
  public static final int BINARY_SECRET_KEY_SIZE = 16;

  /**
   * Size of the text version of the receive-file secret key.
   */
  public static final int TEXT_SECRET_KEY_SIZE = 32;

  public static final String TEMP_PREFIX = "exopackage_temp-";

  public static String getJarSignature(String packagePath) throws IOException {
    Pattern signatureFilePattern = Pattern.compile("META-INF/[A-Z]+\\.SF");

    ZipFile packageZip = null;
    try {
      packageZip = new ZipFile(packagePath);
      // For each file in the zip.
      for (ZipEntry entry : Collections.list(packageZip.entries())) {
        // Ignore non-signature files.
        if (!signatureFilePattern.matcher(entry.getName()).matches()) {
          continue;
        }

        BufferedReader sigContents = null;
        try {
          sigContents =
              new BufferedReader(
                  new InputStreamReader(
                      packageZip.getInputStream(entry)));
          // For each line in the signature file.
          while (true) {
            String line = sigContents.readLine();
            if (line == null || line.equals("")) {
              throw new IllegalArgumentException(
                  "Failed to find manifest digest in " + entry.getName());
            }
            String prefix = "SHA1-Digest-Manifest: ";
            if (line.startsWith(prefix)) {
              return line.substring(prefix.length());
            }
          }
        } finally {
          if (sigContents != null) {
            sigContents.close();
          }
        }
      }
    } finally {
      if (packageZip != null) {
        packageZip.close();
      }
    }

    throw new IllegalArgumentException("Failed to find signature file.");
  }
}
