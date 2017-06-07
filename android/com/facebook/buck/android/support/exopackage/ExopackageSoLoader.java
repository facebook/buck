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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
  private static String nativeLibsDir = null;

  private static File privateNativeLibsDir = null;
  private static Map<String, String> abi1Libraries = new HashMap<String, String>();
  private static Map<String, String> abi2Libraries = new HashMap<String, String>();

  private ExopackageSoLoader() {}

  public static void init(Context context) {
    if (initialized) {
      Log.d(TAG, "init() already called, so nothing to do.");
      return;
    }
    nativeLibsDir = "/data/local/tmp/exopackage/" + context.getPackageName() + "/native-libs/";
    verifyMetadataFile();

    preparePrivateDirectory(context);
    parseMetadata();
    initialized = true;
  }

  private static void verifyMetadataFile() {
    File abiMetadata = getAbi1Metadata();
    if (abiMetadata.exists()) {
      return;
    }
    abiMetadata = getAbi2Metadata();
    if (abiMetadata == null || abiMetadata.exists()) {
      return;
    }
    throw new RuntimeException("Either 'native' exopackage is not turned on for this build, " +
        "or the installation did not complete successfully.");
  }

  private static void preparePrivateDirectory(Context context) {
    privateNativeLibsDir = context.getDir("exo-libs", Context.MODE_PRIVATE);
    for (File file : privateNativeLibsDir.listFiles()) {
      file.delete();
    }
  }

  private static void parseMetadata() {
    doParseMetadata(getAbi1Metadata(), abi1Libraries);
    doParseMetadata(getAbi2Metadata(), abi2Libraries);
  }

  private static void doParseMetadata(File metadata, Map<String, String> libraries) {
    if (metadata == null || !metadata.exists()) {
      return;
    }

    BufferedReader br = null;
    try {
      br = new BufferedReader(new FileReader(metadata));
      String line;
      try {
        while ((line = br.readLine()) != null) {
          line = line.trim();
          if (line.isEmpty()) {
            continue;
          }
          int spaceIndex = line.indexOf(' ');
          if (spaceIndex == -1) {
            throw new RuntimeException("Error parsing metadata.txt; invalid line: " + line);
          }
          String libname = line.substring(0, spaceIndex);
          String filename = line.substring(spaceIndex + 1);
          libraries.put(libname, filename);
        }
      } finally {
        br.close();
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void loadLibrary(String shortName) throws UnsatisfiedLinkError {
    if (!initialized) {
      Log.d(TAG, "ExopackageSoLoader not initialized, falling back to System.loadLibrary()");
      System.loadLibrary(shortName);
      return;
    }

    String libname = shortName.startsWith("lib") ? shortName : "lib" + shortName;

    File libraryFile = copySoFileIfRequired(libname);

    if (libraryFile == null) {
      throw new UnsatisfiedLinkError("Could not find library file for either ABIs.");
    }

    String path = libraryFile.getAbsolutePath();

    Log.d(TAG, "Attempting to load library: " + path);
    System.load(path);
    Log.d(TAG, "Successfully loaded library: " + path);
  }

  private static File copySoFileIfRequired(String libname) {
    File libraryFile = new File(privateNativeLibsDir, libname + ".so");
    if (libraryFile.exists()) {
      return libraryFile;
    }

    if (!abi1Libraries.containsKey(libname) && !abi2Libraries.containsKey(libname)) {
      return null;
    }

    String abiDir;
    String sourceFilename;
    if (abi1Libraries.containsKey(libname)) {
      sourceFilename = abi1Libraries.get(libname);
      abiDir = Build.CPU_ABI;
    } else {
      sourceFilename = abi2Libraries.get(libname);
      abiDir = Build.CPU_ABI2;
    }
    String sourcePath = nativeLibsDir + abiDir + "/" + sourceFilename;

    try {
      InputStream in = null;
      OutputStream out = null;
      try {
        in = new BufferedInputStream(new FileInputStream(sourcePath));
        out = new BufferedOutputStream(new FileOutputStream(libraryFile));
        byte[] buffer = new byte[4 * 1024];
        int len;
        while ((len = in.read(buffer)) > 0) {
          out.write(buffer, 0, len);
        }
      } finally {
        if (in != null) {
          in.close();
        }
        if (out != null) {
          out.close();
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return libraryFile;
  }

  private static File getAbi1Metadata() {
    return new File(nativeLibsDir + Build.CPU_ABI + "/metadata.txt");
  }

  private static File getAbi2Metadata() {
    if (Build.CPU_ABI2.equals("unknown")) {
      return null;
    }

    return new File(nativeLibsDir + Build.CPU_ABI2 + "/metadata.txt");
  }
}
