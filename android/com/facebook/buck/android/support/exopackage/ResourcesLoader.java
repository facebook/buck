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

package com.facebook.buck.android.support.exopackage;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.KITKAT;
import static android.os.Build.VERSION_CODES.LOLLIPOP;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.ArrayMap;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class ResourcesLoader {

  private static final String TAG = "ResourcesLoader";

  private static final int NOUGAT = 24;
  private static Resources sResourcesFromPackageManager = null;
  private static AssetManagerInternal sAssetManager = null;
  private static String sOriginalResourcePath = null;
  private static List<String> sExoResourcesPaths = null;

  private static class AssetManagerInternal {

    private static Method sAddAssetPathMethod;
    private static Method sEnsureStringBlocksMethod;
    private static Method sAddAssetPathAsSharedLibMethod;

    static {
      try {
        // ensureStringBlocks is required on KitKat and doesn't hurt elsewhere.
        sEnsureStringBlocksMethod = AssetManager.class.getDeclaredMethod("ensureStringBlocks");
        sEnsureStringBlocksMethod.setAccessible(true);

        sAddAssetPathMethod = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
        sAddAssetPathMethod.setAccessible(true);
        if (SDK_INT >= NOUGAT) {
          sAddAssetPathAsSharedLibMethod =
              AssetManager.class.getDeclaredMethod("addAssetPathAsSharedLibrary", String.class);
          sAddAssetPathAsSharedLibMethod.setAccessible(true);
        } else {
          sAddAssetPathAsSharedLibMethod = sAddAssetPathMethod;
        }
      } catch (NoSuchMethodException e) {
        throw new RuntimeException(e);
      }
    }

    private final AssetManager assetManager;

    AssetManagerInternal()
        throws NoSuchMethodException, IllegalAccessException, InvocationTargetException,
            InstantiationException {
      this.assetManager = AssetManager.class.getConstructor().newInstance();
    }

    void addAssetPaths(Iterable<String> paths, boolean asSharedLib)
        throws InvocationTargetException, IllegalAccessException {
      for (String path : paths) {
        if (asSharedLib) {
          if ((int) sAddAssetPathAsSharedLibMethod.invoke(assetManager, path) == 0) {
            throw new RuntimeException("Unable to add resources.");
          }
        } else {
          if ((int) sAddAssetPathMethod.invoke(assetManager, path) == 0) {
            throw new RuntimeException("Unable to add resources.");
          }
        }
      }
      ensureStringBlocks();
    }

    private void ensureStringBlocks() throws InvocationTargetException, IllegalAccessException {
      sEnsureStringBlocksMethod.invoke(assetManager);
    }

    AssetManager get() {
      return assetManager;
    }
  }

  private static class ResourcesInternal {

    private final Resources resources;

    ResourcesInternal(Resources resources) {
      this.resources = resources;
    }

    void setAssetManager(AssetManager assetManager)
        throws NoSuchFieldException, IllegalAccessException {
      try {
        Reflect.setField(resources, Resources.class, "mAssets", assetManager);
      } catch (NoSuchFieldException e) {
        Object resourcesImpl = Reflect.getField(resources, Resources.class, "mResourcesImpl");
        Reflect.setField(resourcesImpl, resourcesImpl.getClass(), "mAssets", assetManager);
      }
      resources.updateConfiguration(resources.getConfiguration(), resources.getDisplayMetrics());
      // Should this clear caches like instant-run?
    }
  }

  private static class ResourcesManagerInternal {

    private static Class<?> sResourcesManagerClass;
    private static Method sGetInstanceMethod;

    static {
      try {
        sResourcesManagerClass = Class.forName("android.app.ResourcesManager");
        sGetInstanceMethod = sResourcesManagerClass.getDeclaredMethod("getInstance");
        sGetInstanceMethod.setAccessible(true);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    private final Object resourcesManager;

    private ResourcesManagerInternal(Object resourcesManager) {
      this.resourcesManager = resourcesManager;
    }

    public static ResourcesManagerInternal getInstance()
        throws InvocationTargetException, IllegalAccessException {
      return new ResourcesManagerInternal(sGetInstanceMethod.invoke(sResourcesManagerClass));
    }

    public ArrayMap<?, WeakReference<Resources>> getActiveResources()
        throws NoSuchFieldException, IllegalAccessException {
      return (ArrayMap<?, WeakReference<Resources>>)
          Reflect.getField(resourcesManager, sResourcesManagerClass, "mActiveResources");
    }

    public Collection<WeakReference<Resources>> getResourceReferences()
        throws NoSuchFieldException, IllegalAccessException {
      return (Collection<WeakReference<Resources>>)
          Reflect.getField(resourcesManager, sResourcesManagerClass, "mResourceReferences");
    }

    @TargetApi(NOUGAT)
    public ArrayMap<?, WeakReference<?>> getResourceImpls()
        throws NoSuchFieldException, IllegalAccessException {
      return (ArrayMap<?, WeakReference<?>>)
          Reflect.getField(resourcesManager, sResourcesManagerClass, "mResourceImpls");
    }

    public void setResourceImpls(ArrayMap<Object, Object> resourceImpls)
        throws NoSuchFieldException, IllegalAccessException {
      Reflect.setField(resourcesManager, sResourcesManagerClass, "mResourceImpls", resourceImpls);
    }
  }

  private static class ActivityThreadInternal {

    private static Class<?> sActivityThreadClass;
    private static Method sGetCurrentActivityThreadMethod;

    static {
      try {
        sActivityThreadClass = Class.forName("android.app.ActivityThread");
        sGetCurrentActivityThreadMethod = sActivityThreadClass.getMethod("currentActivityThread");
        sGetCurrentActivityThreadMethod.setAccessible(true);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    private final Object activityThread;

    private ActivityThreadInternal(Object activityThread) {
      this.activityThread = activityThread;
    }

    public static ActivityThreadInternal currentActivityThread()
        throws InvocationTargetException, IllegalAccessException {
      return new ActivityThreadInternal(sGetCurrentActivityThreadMethod.invoke(null));
    }

    public HashMap<?, WeakReference<Resources>> getActiveResources()
        throws NoSuchFieldException, IllegalAccessException {
      return (HashMap<?, WeakReference<Resources>>)
          Reflect.getField(activityThread, sActivityThreadClass, "mActiveResources");
    }

    @TargetApi(KITKAT)
    public List<LoadedApkInternal> getLoadedApks()
        throws NoSuchFieldException, IllegalAccessException {
      List<LoadedApkInternal> loadedApks = new ArrayList<>();
      for (String field : new String[] {"mPackages", "mResourcePackages"}) {
        ArrayMap packages =
            (ArrayMap) Reflect.getField(activityThread, sActivityThreadClass, field);
        for (Object loadedApkRef : packages.values()) {
          Object loadedApk = ((WeakReference) loadedApkRef).get();
          if (loadedApk == null) {
            continue;
          }
          loadedApks.add(new LoadedApkInternal(loadedApk));
        }
      }
      return loadedApks;
    }
  }

  private static class LoadedApkInternal {

    private static Class<?> sLoadedApkClass;

    static {
      try {
        sLoadedApkClass = Class.forName("android.app.LoadedApk");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    private final Object loadedApk;

    private LoadedApkInternal(Object loadedApk) {
      this.loadedApk = loadedApk;
    }

    public Context getApplication() throws NoSuchFieldException, IllegalAccessException {
      return (Context) Reflect.getField(loadedApk, sLoadedApkClass, "mApplication");
    }

    public void setResDir(String resDir) throws NoSuchFieldException, IllegalAccessException {
      Reflect.setField(loadedApk, sLoadedApkClass, "mResDir", resDir);
    }

    public void setSplitResDirs(String[] splitResDirs)
        throws NoSuchFieldException, IllegalAccessException {
      Reflect.setField(loadedApk, sLoadedApkClass, "mSplitResDirs", splitResDirs);
    }
  }

  private static class ResourcesKeyInternal {
    private static Class<?> sResourcesKeyClass;

    static {
      try {
        sResourcesKeyClass = Class.forName("android.content.res.ResourcesKey");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    Object key;

    public ResourcesKeyInternal(Object key) {
      this.key = key;
    }

    void setResDir(String resDir) throws NoSuchFieldException, IllegalAccessException {
      Reflect.setField(key, sResourcesKeyClass, "mResDir", resDir);
    }

    void setSplitResDirs(String[] splitResDirs)
        throws NoSuchFieldException, IllegalAccessException {
      Reflect.setField(key, sResourcesKeyClass, "mSplitResDirs", splitResDirs);
    }

    public String getResDir() throws NoSuchFieldException, IllegalAccessException {
      return (String) Reflect.getField(key, sResourcesKeyClass, "mResDir");
    }
  }

  private ResourcesLoader() {}

  // init() will replace the underlying AssetManager for all currently created resources. It should
  // be called in Application.attachBaseContext() if the app is going to access resources before
  // Application.onCreate().
  public static void init(Context context) {
    try {
      Log.e(TAG, "Initializing ResourcesLoader.");
      sOriginalResourcePath = context.getPackageResourcePath();
      hookAssetManagers(context);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // This should be called as soon as possible after Application.attachBaseContext() hasReturned
  // (Application.onCreate() for example). It modifies a LoadedApk object that is not created until
  // after that point. This data in the LoadedApk is used to create new Resources objects (on
  // Android L+).
  public static void onAppCreated(Context context) {
    try {
      Log.e(TAG, "Updating more internals for ResourcesLoader.");
      if (SDK_INT >= LOLLIPOP) {
        updateLoadedApkResDirs(context);
      }
      if (SDK_INT >= NOUGAT) {
        // In some cases, Android's ResourcesManager will derive new Resources from an existing
        // ResourceKey -> ResourceImpl mapping. Before LoadedApk is updated, those keys won't have
        // the correct res/splitResDirs, and so we need to create the mapping with the correct
        // ResourceKeys.
        updateResourceKeys(context, sOriginalResourcePath);
      }
      // This Resources instance should have the same ResourceKey as any future Resources acquired
      // through the ApplicationInfo returned from the PackageManager and so should map to this
      // hooked instance.
      sResourcesFromPackageManager =
          context.getPackageManager().getResourcesForApplication(context.getPackageName());
      hookAssetManagers(context);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @TargetApi(NOUGAT)
  private static void updateResourceKeys(Context context, String originalResourcePath)
      throws InvocationTargetException, IllegalAccessException, NoSuchFieldException, IOException {
    List<String> exoResourcePaths = getExoPaths(context);
    if (exoResourcePaths.isEmpty()) {
      return;
    }
    String resDir = exoResourcePaths.get(0);
    String[] splitResDirs =
        exoResourcePaths
            .subList(1, exoResourcePaths.size())
            .toArray(new String[exoResourcePaths.size() - 1]);

    ArrayMap<?, ?> resourceImpls = ResourcesManagerInternal.getInstance().getResourceImpls();
    ArrayMap<Object, Object> newResourceImpls = new ArrayMap<>(resourceImpls.size());
    for (Map.Entry<?, ?> entry : resourceImpls.entrySet()) {
      Object key = entry.getKey();
      ResourcesKeyInternal keyInternal = new ResourcesKeyInternal(key);
      if (keyInternal.getResDir().equals(originalResourcePath)) {
        keyInternal.setResDir(resDir);
        keyInternal.setSplitResDirs(splitResDirs);
        newResourceImpls.put(key, entry.getValue());
      }
    }
    ResourcesManagerInternal.getInstance().setResourceImpls(newResourceImpls);
  }

  public static void hookAssetManagers(Context context) throws Exception {
    AssetManagerInternal assetManager = getAssetManager(context);
    Collection<WeakReference<Resources>> activeResources = getActiveResources();
    for (WeakReference<Resources> ref : activeResources) {
      Resources res = ref.get();
      if (res == null) {
        continue;
      }
      new ResourcesInternal(res).setAssetManager(assetManager.get());
    }
  }

  private static List<String> getWebViewAssets(Context context)
      throws InvocationTargetException, IllegalAccessException, NoSuchMethodException,
          ClassNotFoundException, PackageManager.NameNotFoundException {
    if (SDK_INT >= NOUGAT) {
      Class webViewFactoryClass = Class.forName("android.webkit.WebViewFactory");
      Method getWebviewContextAndSetProviderMethod =
          webViewFactoryClass.getDeclaredMethod("getWebViewContextAndSetProvider");
      getWebviewContextAndSetProviderMethod.setAccessible(true);
      Context webViewContext =
          (Context) getWebviewContextAndSetProviderMethod.invoke(webViewFactoryClass);
      return Collections.singletonList(webViewContext.getApplicationInfo().sourceDir);
    } else if (SDK_INT >= LOLLIPOP) {
      Class webViewFactoryClass = Class.forName("android.webkit.WebViewFactory");
      Method getWebViewPackageNameMethod =
          webViewFactoryClass.getDeclaredMethod("getWebViewPackageName");
      getWebViewPackageNameMethod.setAccessible(true);
      String packageName = (String) getWebViewPackageNameMethod.invoke(webViewFactoryClass);
      PackageInfo packageInfo =
          context.getPackageManager().getPackageInfo(packageName, PackageManager.GET_META_DATA);
      return Collections.singletonList(packageInfo.applicationInfo.sourceDir);
    }
    return Collections.emptyList();
  }

  private static AssetManagerInternal getAssetManager(Context context)
      throws ClassNotFoundException, NoSuchMethodException, IllegalAccessException,
          InvocationTargetException, IOException, InstantiationException {
    if (sAssetManager == null) {
      List<String> exoResourcePaths = getExoPaths(context);
      sAssetManager = new AssetManagerInternal();
      sAssetManager.addAssetPaths(exoResourcePaths, false);
    }
    return sAssetManager;
  }

  // On Android L and above, the runtime might create multiple Resources objects for the same
  // process. These new Resources are created from the resource directories specified in the
  // LoadedApk object. Luckily, Android L also introduced split resources which allow us to
  // configure the LoadedApk to construct Resources that reference multiple zip files (if this
  // weren't the case, we could still install resources at runtime, but would be limited to a single
  // zip/directory).
  @TargetApi(LOLLIPOP)
  public static void updateLoadedApkResDirs(Context context) throws Exception {
    List<String> exoResourcePaths = getExoPaths(context);
    if (exoResourcePaths.isEmpty()) {
      return;
    }
    // Split resource loading doesn't interact correctly with WebView's asset loading, so we add
    // the webview assets directly (in the same way that the webview asset-loading does it).
    // If split resource loading didn't replace the base source dir (and just added split resource
    // dirs), I think it would interact fine with webview assets. I'm not sure if this would work
    // on Android <5.0, and it would require using different package id or type ids in the split
    // resources.
    List<String> webviewAssets = getWebViewAssets(context);
    getAssetManager(context).addAssetPaths(webviewAssets, true);
    ApplicationInfo applicationInfo = context.getApplicationInfo();
    LinkedHashSet<String> sharedLibraryFiles = new LinkedHashSet<>();
    if (applicationInfo.sharedLibraryFiles != null) {
      sharedLibraryFiles.addAll(Arrays.asList(applicationInfo.sharedLibraryFiles));
    }
    sharedLibraryFiles.addAll(webviewAssets);
    applicationInfo.sharedLibraryFiles =
        sharedLibraryFiles.toArray(new String[sharedLibraryFiles.size()]);

    String resDir = exoResourcePaths.get(0);
    String[] splitResDirs = new String[exoResourcePaths.size() - 1];
    for (int i = 1; i < exoResourcePaths.size(); i++) {
      splitResDirs[i - 1] = exoResourcePaths.get(i);
    }
    ActivityThreadInternal activityThread = ActivityThreadInternal.currentActivityThread();
    for (LoadedApkInternal loadedApk : activityThread.getLoadedApks()) {
      if (loadedApk.getApplication() == context) {
        loadedApk.setResDir(resDir);
        loadedApk.setSplitResDirs(splitResDirs);
      }
    }
  }

  private static List<String> getExoPaths(Context context) throws IOException {
    if (sExoResourcesPaths == null) {
      sExoResourcesPaths = readMetadata(context);
    }
    return sExoResourcesPaths;
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
      return ActivityThreadInternal.currentActivityThread().getActiveResources().values();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @TargetApi(KITKAT)
  private static Collection<WeakReference<Resources>> getActiveResourcesFromResourcesManager() {
    if (SDK_INT < KITKAT) {
      return null;
    }
    try {
      ResourcesManagerInternal resourcesManager = ResourcesManagerInternal.getInstance();
      if (SDK_INT < NOUGAT) {
        return resourcesManager.getActiveResources().values();
      }
      return resourcesManager.getResourceReferences();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static List<String> readMetadata(Context context) throws IOException {
    File resourcesDirectory =
        new File("/data/local/tmp/exopackage/" + context.getPackageName() + "/resources");
    BufferedReader br = null;
    try {
      br = new BufferedReader(new FileReader(new File(resourcesDirectory, "metadata.txt")));
      ArrayList<String> resources = new ArrayList<>();
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
        resources.add(apk.getAbsolutePath());
      }
      return resources;
    } finally {
      if (br != null) {
        br.close();
      }
    }
  }
}
