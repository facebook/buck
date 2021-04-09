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

package com.facebook.buck.features.project.intellij.targetinfo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/** A small standalone tool to look up keys in binary files for testing. */
public class LookupTool {
  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("Usage: lookuptool <key> <path>");
      System.exit(1);
    }

    String key = args[0];
    String pathString = args[1];
    Path path = new File(pathString).toPath();

    // Try first the TargetInfo
    try {
      print(new TargetInfoBinaryFile(path), key);
    } catch (IOException e) {
      print(new ModuleToTargetsBinaryFile(path), key);
    }
  }

  private static <T> void print(HashFile<String, T> hashFile, String key) throws IOException {
    T info = hashFile.get(key);
    if (info != null) {
      System.out.printf("%s=%s\n", key, info);
    } else {
      System.out.println("No value found for key " + key + " in " + hashFile.getPath());
    }
  }
}
