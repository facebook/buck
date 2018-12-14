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

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Application;
import android.app.Fragment;
import android.app.Fragment.SavedState;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.os.Process;
import android.view.View;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Support and Utility Functions for Exopackage, including hotswap helper functions */
public class ExoHelper {

  private static boolean sIsHotswapSetup = false;

  private static final List<OnModulesChangedCallback> sCallbacks =
      new ArrayList<OnModulesChangedCallback>();

  /**
   * Restart the given activity. The same intent will be used to launch the new activity, preserving
   * actions, extras, etc. Must be run on the main thread.
   *
   * @param activity the activity to restart
   */
  public static void restartActivity(Activity activity) {
    assertIsUiThread();
    final Intent intent = activity.getIntent();
    activity.startActivity(intent);
    activity.finish();
  }

  /**
   * Create an Intent to launch the component with the given classname. This is a simple wrapper
   * around Intent#setComponent with the given params.
   *
   * @param context the current context
   * @param className the name of the class targeted by the Intent
   * @return an Intent suitable for launching the component with the given className
   */
  public static Intent intentForClassName(Context context, String className) {
    final Intent intent = new Intent();
    intent.setComponent(new ComponentName(context, className));
    return intent;
  }

  /**
   * Create a new Fragment instance. This should be preferred to Fragment#instantiate() due to
   * caching of the lookup class.
   *
   * @param className the name of the fragment subclass to instantiate
   * @param args optional args to pass to the fragment, or null if no args
   * @return a new instance of the given Fragment class
   * @throws RuntimeException if the given className cannot be found or instantiated as a fragment
   */
  public static Fragment createFragment(String className, Bundle args) {
    try {
      final Fragment instance = (Fragment) Class.forName(className).newInstance();
      instance.setArguments(args);
      return instance;
    } catch (IllegalAccessException | InstantiationException e) {
      throw new RuntimeException("Could not instantiate " + className, e);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(className + " could not be found", e);
    } catch (ClassCastException e) {
      throw new RuntimeException("Fragment is not assignable from " + className, e);
    }
  }

  /**
   * Refresh the given fragment in place, preserving construction args, and any saved state. The new
   * fragment will be constructed via a fresh class lookup. This method must be called from the UI
   * thread
   *
   * @param fragment the fragment to refresh
   */
  public static void refreshFragment(Fragment fragment) {
    refreshFragment(fragment, true);
  }

  /**
   * Try refreshing the given Fragment in place, preserving construction args. The new Fragment will
   * be constructed via a fresh class lookup. This method must be called from the UI thread
   *
   * @param fragment the fragment to refresh
   * @param preserveState if true, attempt to save the Fragment's current state and restore it to
   *     the new instance of the fragment
   */
  public static void refreshFragment(Fragment fragment, boolean preserveState) {
    assertIsUiThread();
    FragmentManager manager = fragment.getFragmentManager();
    final Bundle args = fragment.getArguments();
    Fragment replacement = createFragment(fragment.getClass().getName(), args);

    String tag = fragment.getTag();

    if (preserveState) {
      // Restore any state that's possible
      final SavedState savedState = manager.saveFragmentInstanceState(fragment);
      replacement.setInitialSavedState(savedState);
    }

    int containerViewId = ((View) fragment.getView().getParent()).getId();
    final FragmentTransaction transaction = manager.beginTransaction();
    transaction.remove(fragment);
    if (tag != null) {
      transaction.add(containerViewId, replacement, tag);
    } else {
      transaction.add(containerViewId, replacement);
    }
    transaction.commit();
  }

  /**
   * Try to instantiate a support Fragment with the given classname. This should be preferred to
   * Fragment#instantiate() due to caching of the lookup class.
   *
   * @param className the name of the fragment subclass to instantiate
   * @param args optional args to pass to the fragment, or null if no args
   * @return null if the given class does not exist, or could not be instantiated, otherwise an
   *     instance of the Fragment subclass.
   * @throws RuntimeException if the given className cannot be found or instantiated as a fragment
   */
  public static android.support.v4.app.Fragment createSupportFragment(
      String className, Bundle args) {
    try {
      final android.support.v4.app.Fragment instance =
          (android.support.v4.app.Fragment) Class.forName(className).newInstance();
      instance.setArguments(args);
      return instance;
    } catch (IllegalAccessException | InstantiationException e) {
      throw new RuntimeException("Could not instantiate " + className, e);
    } catch (ClassNotFoundException | ClassCastException e) {
      throw new RuntimeException(
          className + " is not a valid Fragment subclass or could not be found", e);
    }
  }

  /**
   * Refresh the given fragment in place, preserving construction args, and any saved state. The new
   * fragment will be constructed via a fresh class lookup.
   *
   * @param fragment the fragment to refresh
   */
  public static void refreshFragment(android.support.v4.app.Fragment fragment) {
    refreshFragment(fragment, true);
  }

  /**
   * Try refreshing the given Fragment in place, preserving construction args. The new Fragment will
   * be constructed via a fresh class lookup.
   *
   * @param fragment the fragment to refresh
   * @param preserveState if true, attempt to save the Fragment's current state and restore it to
   *     the new instance of the fragment
   */
  public static void refreshFragment(
      android.support.v4.app.Fragment fragment, boolean preserveState) {
    assertIsUiThread();
    android.support.v4.app.FragmentManager manager = fragment.getFragmentManager();
    final Bundle args = fragment.getArguments();
    android.support.v4.app.Fragment replacement =
        createSupportFragment(fragment.getClass().getName(), args);

    String tag = fragment.getTag();

    if (preserveState) {
      // Restore any state that's possible
      final android.support.v4.app.Fragment.SavedState savedState =
          manager.saveFragmentInstanceState(fragment);
      replacement.setInitialSavedState(savedState);
    }

    int containerViewId = ((View) fragment.getView().getParent()).getId();
    final android.support.v4.app.FragmentTransaction transaction = manager.beginTransaction();
    transaction.remove(fragment);
    if (tag != null) {
      transaction.add(containerViewId, replacement, tag);
    } else {
      transaction.add(containerViewId, replacement);
    }
    transaction.commit();
  }

  /**
   * Restart the application by setting a PendingIntent on the AlarmManager and then killing the
   * current process.
   *
   * @param context any current context from the application
   */
  public static void restartApp(Context context) {
    Context appContext = context.getApplicationContext();
    final Intent launchIntent =
        appContext
            .getPackageManager()
            .getLaunchIntentForPackage(appContext.getPackageName())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    // Can be anything so long as it's unique. This part of the sha1sum of "buck"
    int id = 0xe354735f;
    final int flags =
        PendingIntent.FLAG_CANCEL_CURRENT
            | PendingIntent.FLAG_IMMUTABLE
            | PendingIntent.FLAG_ONE_SHOT;
    PendingIntent pendingIntent = PendingIntent.getActivity(appContext, id, launchIntent, flags);
    AlarmManager am = appContext.getSystemService(AlarmManager.class);
    long deadline = System.currentTimeMillis() + 500L;
    am.setExact(AlarmManager.RTC_WAKEUP, deadline, pendingIntent);
    Process.killProcess(Process.myPid());
  }

  /**
   * Enable hotswapping on an application instance. This call is not necessary if using {@link
   * ExopackageApplication}. If enabling manually, make sure the app is set up to support the
   * "modules" exopackage mode, and the initial call to {@link
   * ExopackageDexLoader#loadExopackageJars(Context, boolean)} passed true as the second arg
   */
  public static synchronized void setupHotswap(Application application) {
    if (!sIsHotswapSetup) {
      final File dexOptDir = application.getDir("exopackage_modular_dex_opt", Context.MODE_PRIVATE);
      DelegatingClassLoader.getInstance().setDexOptDir(dexOptDir);
      application.registerReceiver(
          new ModularDexChangedReceiver(),
          ModularDexChangedReceiver.getIntentFilter(application.getPackageName()));

      sIsHotswapSetup = true;
    }
  }

  /**
   * Add a callback which will fire whenever code definitions have changed in the app. This callback
   * will be executed on the main thread.
   *
   * @param callback the callback to add
   */
  public static void addOnModulesChangedCallback(OnModulesChangedCallback callback) {
    synchronized (sCallbacks) {
      sCallbacks.add(callback);
    }
  }

  /**
   * Remove a previously added callback
   *
   * @param callback the callback to remove
   */
  public static void removeOnModulesChangedCallback(OnModulesChangedCallback callback) {
    synchronized (sCallbacks) {
      sCallbacks.remove(callback);
    }
  }

  /**
   * Trigger all callbacks which have registered to receive notifications when the module
   * definitions have changed on disk
   */
  static void triggerCallbacks() {
    synchronized (sCallbacks) {
      for (OnModulesChangedCallback mCallback : sCallbacks) {
        mCallback.onModulesChanged();
      }
    }
  }

  private static void assertIsUiThread() {
    if (Looper.getMainLooper().getThread() != Thread.currentThread()) {
      throw new RuntimeException("This method must be called from the UI thread");
    }
  }
}
