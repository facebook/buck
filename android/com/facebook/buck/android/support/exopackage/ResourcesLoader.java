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
    Metadata metadata = readMetadata(resourcesDirectory);

    if (!metadata.hasResources()) {
      Log.w(TAG, "No exo-resources found in: " + resourcesDirectory.getName());
      return;
    }

    AssetManager assetManager = AssetManager.class.getConstructor().newInstance();
    Method addAssetPathMethod =
        AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
    addAssetPathMethod.setAccessible(true);
    if (metadata.hasPrimaryResources()) {
      int cookie =
          (int) addAssetPathMethod.invoke(assetManager, metadata.getPrimaryResourcesPath());
      if (cookie == 0) {
        throw new RuntimeException("Unable to add resources");
      }
    }
    for (String assetsPath : metadata.getAssetsPaths()) {
      int cookie = (int) addAssetPathMethod.invoke(assetManager, assetsPath);
      if (cookie == 0) {
        throw new RuntimeException("Unable to add assets");
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

  private static Metadata readMetadata(File resourcesDirectory) throws IOException {
    BufferedReader br = null;
    try {
      br = new BufferedReader(new FileReader(
          new File(resourcesDirectory, "metadata.txt")));
      File resources = null;
      ArrayList<String> assets = new ArrayList<>();
      for (String line; (line = br.readLine()) != null; ) {
        String[] values = line.split(" ");
        if (values.length != 2) {
          throw new RuntimeException("Bad metadata for resources... (" + line + ")");
        }
        switch (values[0]) {
          case "resources":
            resources = new File(resourcesDirectory, values[1] + ".apk");
            if (!resources.exists()) {
              throw new RuntimeException("resources don't exist... (" + line + ")");
            }
            break;
          case "assets":
            File asset = new File(resourcesDirectory, values[1] + ".apk");
            if (!asset.exists()) {
              throw new RuntimeException("resources don't exist... (" + line + ")");
            }
            assets.add(asset.getAbsolutePath());
            break;
          default:
            throw new RuntimeException("Unrecognized resource type: (" + line + ")");
        }
      }
      return new Metadata(resources.getAbsolutePath(), assets);
    } finally {
      if (br != null) {
        br.close();
      }
    }
  }

  private static class Metadata {
    private final String primaryResources;
    private final ArrayList<String> assets;

    Metadata(String primaryResources, ArrayList<String> assets) {
      this.primaryResources = primaryResources;
      this.assets = assets;
    }

    public boolean hasResources() {
      return hasPrimaryResources() || !assets.isEmpty();
    }

    public boolean hasPrimaryResources() {
      return primaryResources != null;
    }

    public String getPrimaryResourcesPath() {
      return primaryResources;
    }

    public Iterable<String> getAssetsPaths() {
      return assets;
    }
  }
}
