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

package com.sample.app.app_with_module_loader;

import android.app.Application;
import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

public class ApplicationWithModuleLoader extends Application {
  private static String name = "ApplicationWithModuleLoader";
  private AssetManager baseAssetManager;

  @Override
  protected void attachBaseContext(Context base) {
    super.attachBaseContext(base);
    baseAssetManager = base.getAssets();
    File featurePath = new File(base.getApplicationInfo().dataDir, "feature1.apk");

    Log.i(name, "enter attachBaseContext");

    try {
      AssetManager.class
          .getMethod("addAssetPath", String.class)
          .invoke(baseAssetManager, featurePath.getPath());

      ClassLoader appClassLoader = ApplicationWithModuleLoader.class.getClassLoader();
      ClassLoader systemClassLoader = appClassLoader.getParent();
      Field classLoaderParentField = ClassLoader.class.getDeclaredField("parent");
      classLoaderParentField.setAccessible(true);

      PathClassLoader apkLoader =
          new PathClassLoader(base.getApplicationInfo().sourceDir, systemClassLoader);
      PathClassLoader featureLoader = new PathClassLoader(featurePath.getPath(), apkLoader);
      classLoaderParentField.set(appClassLoader, featureLoader);

      Log.i(name, "class loader for feature has been configured");

    } catch (IllegalAccessException e) {
      e.printStackTrace();
    } catch (InvocationTargetException e) {
      e.printStackTrace();
    } catch (NoSuchMethodException e) {
      e.printStackTrace();
    } catch (NoSuchFieldException e) {
      e.printStackTrace();
    }

    Log.i(name, "exit attachBaseContext");
  }
}
