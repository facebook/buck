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

package com.facebook.buck.android.support.exopackage;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * Loads native library files installed by the exopackage installer. This class requires
 * initialization before use; {@link ExopackageApplication#attachBaseContext} is responsible
 * for calling {@link #init} so that application-specific code doesn't need to.
 */
public class ExopackageSoLoader {

  private static final String TAG = "ExopackageSoLoader";

  private static boolean initialized = false;

  private static Map<String, String> cpuAbi1Libraries = new HashMap<>();
  private static Map<String, String> cpuAbi2Libraries = new HashMap<>();

  private ExopackageSoLoader() {}

  public static void init(Context context) {
    if (initialized) {
      Log.d(TAG, "init() already called, so nothing to do.");
      return;
    }
    try {
      collectLibraries(context, Build.CPU_ABI, cpuAbi1Libraries);
      collectLibraries(context, Build.CPU_ABI2, cpuAbi2Libraries);
      initialized = true;
      Log.i(TAG, "Finished initializing.");
    } catch (IOException e) {
      Log.e(TAG, "There was an error initializing: ", e);
      cpuAbi1Libraries.clear();
      cpuAbi2Libraries.clear();
      return;
    }
  }

  public static void collectLibraries(Context context, String abi, Map<String, String> libraries)
      throws IOException {
    if (abi == null || abi.isEmpty()) {
      return;
    }

    String abiDirectory = "/data/local/tmp/exopackage/" + context.getPackageName() +
        "/native-libs/" + abi;

    File metadataFile = new File(abiDirectory + "/metadata.txt");
    if (!metadataFile.exists()) {
      Log.d(TAG, "Could not find metadata file: " + metadataFile.getAbsolutePath());
      return;
    }

    BufferedReader reader = null;
    try {
      Log.d(TAG, "Reading metadata file: " + metadataFile.getAbsolutePath());
      reader = new BufferedReader(new FileReader(metadataFile.getAbsolutePath()));
      String line = null;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) {
          continue;
        }
        int index = line.indexOf(' ');
        if (index == -1) {
          Log.w(TAG, "Skipping corrupt metadata line: \n" + line + "\n");
          continue;
        }

        Log.v(TAG, "Processing line: " + line);
        libraries.put(line.substring(0, index), abiDirectory + "/" + line.substring(index + 1));
      }
    } finally {
      if (reader != null) {
        reader.close();
      }
    }
  }

  public static void loadLibrary(String library) {
    String path = cpuAbi1Libraries.get(library);
    if (path == null) {
      path = cpuAbi2Libraries.get(library);
    }
    if (path == null) {
      Log.e(TAG, "Could not load library " + library);
      return;
    }

    Log.d(TAG, "Attempting to load library: " + path);
    System.load(path);
    Log.d(TAG, "Successfully loaded library: " + path);
  }
}
