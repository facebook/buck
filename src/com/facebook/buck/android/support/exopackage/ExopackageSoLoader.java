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
  private static String nativeLibsDir = null;

  private ExopackageSoLoader() {}

  public static void init(Context context) {
    if (initialized) {
      Log.d(TAG, "init() already called, so nothing to do.");
      return;
    }
    nativeLibsDir = "/data/local/tmp/exopackage/" + context.getPackageName() + "/native-libs/";
    verifyMetadataFile();
  }

  private static void verifyMetadataFile() {
    File abiMetadata = new File(nativeLibsDir + Build.CPU_ABI + "/metadata.txt");
    if (abiMetadata.exists()) {
      return;
    }
    if (!Build.CPU_ABI2.equals("unknown")) {
      abiMetadata = new File(nativeLibsDir + Build.CPU_ABI2 + "/metadata.txt");
      if (abiMetadata.exists()) {
        return;
      }
    }
    throw new RuntimeException("Either 'native' exopackage is not turned on for this build, " +
        "or the installation did not complete successfully.");
  }

  public static void loadLibrary(String shortName) throws UnsatisfiedLinkError {
    String filename = shortName.startsWith("lib") ? shortName : "lib" + shortName;

    File libraryFile = new File(nativeLibsDir + Build.CPU_ABI + "/" + filename + ".so");
    if (!libraryFile.exists()) {
      libraryFile = new File(nativeLibsDir + Build.CPU_ABI2 + "/" + filename + ".so");
      if (!libraryFile.exists()) {
        libraryFile = null;
      }
    }

    if (libraryFile == null) {
      throw new UnsatisfiedLinkError("Could not find library file for either ABIs.");
    }

    String path = libraryFile.getAbsolutePath();

    Log.d(TAG, "Attempting to load library: " + path);
    System.load(path);
    Log.d(TAG, "Successfully loaded library: " + path);
  }
}
