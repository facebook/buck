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

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads pre-dexed jars installed by the exopackage installer into our ClassLoader.
 *
 * <p>See http://android-developers.blogspot.com/2011/07/custom-class-loading-in-dalvik.html for an
 * explanation of how an app can load pre-dexed libraries. This class goes a step further and then
 * hacks the system class loader so these can be referenced by default. This logic has to run before
 * any class that references the code in these dexes is loaded.
 */
public class ExopackageDexLoader {

  private static final String TAG = "ExopackageDexLoader";

  private ExopackageDexLoader() {}

  /**
   * Load JARs installed by Buck's Exopackage installer and add them to the Application ClassLoader.
   *
   * @param context The application context.
   */
  @SuppressWarnings("PMD.CollapsibleIfStatements")
  public static void loadExopackageJars(Context context) {
    File containingDirectory =
        new File("/data/local/tmp/exopackage/" + context.getPackageName() + "/secondary-dex");

    List<File> dexJars = new ArrayList<>();
    Set<String> expectedOdexSet = new HashSet<>();

    try {
      BufferedReader metadataReader =
          new BufferedReader(
              new InputStreamReader(
                  new FileInputStream(new File(containingDirectory, "metadata.txt"))));
      try {
        String line;
        while ((line = metadataReader.readLine()) != null) {
          int space = line.indexOf(' ');
          if (space == -1) {
            throw new RuntimeException("Found no space in line: " + line);
          }
          String baseName = line.substring(0, space);
          dexJars.add(new File(containingDirectory, baseName));
          expectedOdexSet.add(baseName.replaceFirst("\\.jar$", ".dex"));
        }
      } finally {
        metadataReader.close();
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    File dexOptDir = context.getDir("exopackage_dex_opt", Context.MODE_PRIVATE);
    SystemClassLoaderAdder.installDexJars(context.getClassLoader(), dexOptDir, dexJars);

    File[] odexes = dexOptDir.listFiles();
    if (odexes != null) {
      for (File odex : odexes) {
        if (!expectedOdexSet.contains(odex.getName())) {
          if (!odex.delete()) {
            Log.w(TAG, "Failed to delete stale odex: " + odex.getAbsolutePath());
          }
        }
      }
    }
  }
}
