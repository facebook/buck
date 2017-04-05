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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.ArrayMap;
import android.util.Log;

public class ResourcesLoader {
  private static final String TAG = "ResourcesLoader";

  private ResourcesLoader() {
  }

  public static void init(Context context) {
    try {
      install(context);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static void install(Context context)
      throws Exception {
    File resourcesDirectory = new File(
        "/data/local/tmp/exopackage/" + context.getPackageName() + "/resources");
    List<File> exoResourcePaths = readMetadata(resourcesDirectory);
    if (exoResourcePaths.isEmpty()) {
      Log.w(TAG, "No exo-resources found in: " + resourcesDirectory.getName());
      return;
    }

    AssetManager assetManager = AssetManager.class.getConstructor().newInstance();
    Method addAssetPathMethod =
        AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
    addAssetPathMethod.setAccessible(true);

    for (File resourcePath : exoResourcePaths) {
      int cookie = (int) addAssetPathMethod.invoke(assetManager, resourcePath.getAbsolutePath());
      if (cookie == 0) {
        throw new RuntimeException("Unable to add resources.");
      }
    }

    // Required on KitKat and doesn't hurt elsewhere.
    Method mEnsureStringBlocks = AssetManager.class.getDeclaredMethod("ensureStringBlocks");
    mEnsureStringBlocks.setAccessible(true);
    mEnsureStringBlocks.invoke(assetManager);

    Collection<WeakReference<Resources>> activeResources = getActiveResources();
    for (WeakReference<Resources> ref : activeResources) {
      Resources res = ref.get();
      if (res == null) {
        continue;
      }
      try {
        Reflect.setField(res, Resources.class, "mAssets", assetManager);
      } catch (NoSuchFieldException e) {
        Object resourcesImpl = Reflect.getField(res, Resources.class, "mResourcesImpl");
        Reflect.setField(resourcesImpl, resourcesImpl.getClass(), "mAssets", assetManager);
      }
      res.updateConfiguration(res.getConfiguration(), res.getDisplayMetrics());
    }
  }

  private static Collection<WeakReference<Resources>> getActiveResources() {
    Collection<WeakReference<Resources>> activeResources = getActiveResourcesFromResourcesManager();
    if (activeResources != null) {
      return activeResources;
    }
    return getActiveResourcesFromActivityThread();
  }

  private static Collection<WeakReference<Resources>> getActiveResourcesFromActivityThread() {
    try {
      Class activityThreadClass = Class.forName("android.app.ActivityThread");
      Method m = activityThreadClass.getMethod("currentActivityThread");
      m.setAccessible(true);
      Object currentActivityThread = m.invoke(null);

      return ((HashMap<?, WeakReference<Resources>>) Reflect.getField(
          currentActivityThread,
          activityThreadClass,
          "mActiveResources"
      )).values();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static Collection<WeakReference<Resources>> getActiveResourcesFromResourcesManager() {
    try {
      Class resourcesManagerClass;
      try {
        resourcesManagerClass = Class.forName("android.app.ResourcesManager");
      } catch (ClassNotFoundException e) {
        return null;
      }

      Method getResourcesManager = resourcesManagerClass.getDeclaredMethod("getInstance");
      getResourcesManager.setAccessible(true);
      Object resourcesManager = getResourcesManager.invoke(null);

      try {
        return ((ArrayMap<?, WeakReference<Resources>>) Reflect.getField(
            resourcesManager,
            resourcesManagerClass,
            "mActiveResources")).values();
      } catch (NoSuchFieldException e) {
        return (Collection<WeakReference<Resources>>) Reflect.getField(
            resourcesManager,
            resourcesManagerClass,
            "mResourceReferences"
        );
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

  }

  private static List<File> readMetadata(File resourcesDirectory) throws IOException {
    BufferedReader br = null;
    try {
      br = new BufferedReader(new FileReader(
          new File(resourcesDirectory, "metadata.txt")));
      ArrayList<File> resources = new ArrayList<>();
      for (String line; (line = br.readLine()) != null; ) {
        String[] values = line.split(" ");
        if (values.length != 2) {
          throw new RuntimeException("Bad metadata for resources... (" + line + ")");
        }
        if (!values[0].equals("resources")) {
          throw new RuntimeException("Unrecognized resource type: (" + line + ")");
        }
        File apk = new File(resourcesDirectory, values[1] + ".apk");
        if (!apk.exists()) {
          throw new RuntimeException("resources don't exist... (" + line + ")");
        }
        resources.add(apk);
      }
      return resources;
    } finally {
      if (br != null) {
        br.close();
      }
    }
  }
}
