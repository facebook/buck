/*
 * Copyright 2017-present Facebook, Inc.
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Triggers a refresh of DelegatingClassLoader as well as any user-registered
 * OnModulesChangedCallback instances.
 */
public class ModularDexChangedReceiver extends BroadcastReceiver {

  @Override
  public void onReceive(Context context, Intent intent) {
    String changedPath = intent.getStringExtra("exo_dir");
    File changedDir =
        new File("/data/local/tmp/exopackage/" + context.getPackageName(), changedPath);
    final List<File> dexJars = ExopackageDexLoader.getJarFilesFromContainingDirectory(changedDir);
    DelegatingClassLoader.getInstance().resetDelegate(dexJars);
    List<String> moduleClasses = getModuleClassesFromContainingDirectory(changedDir);
    ExoHelper.triggerCallbacks(moduleClasses);
  }

  /** @return a filter for the broadcast sent by buck when the installation completes */
  static IntentFilter getIntentFilter(String packageName) {
    return new IntentFilter(packageName + "._EXOPACKAGE_DIR_UPDATED");
  }

  /** Find all module classes for the given directory */
  static List<String> getModuleClassesFromContainingDirectory(File containingDirectory) {
    List<String> moduleClasses = new ArrayList();
    try {
      final File moduleClassesFile = new File(containingDirectory, "module_classes.txt");
      if (!moduleClassesFile.exists()) {
        return moduleClasses;
      }

      BufferedReader moduleClassesReader =
          new BufferedReader(new InputStreamReader(new FileInputStream(moduleClassesFile)));
      try {
        String line;
        while ((line = moduleClassesReader.readLine()) != null) {
          moduleClasses.add(line);
        }
      } finally {
        moduleClassesReader.close();
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return moduleClasses;
  }
}
