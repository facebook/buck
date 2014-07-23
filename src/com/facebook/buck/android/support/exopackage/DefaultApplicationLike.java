package com.facebook.buck.android.support.exopackage;

import android.app.Application;

/**
 * Empty implementation of {@link ApplicationLike}.
 */
public class DefaultApplicationLike implements ApplicationLike {
  public DefaultApplicationLike() {}

  @SuppressWarnings("unused")
  public DefaultApplicationLike(Application application) {}

  @Override
  public void onCreate() {}

  @Override
  public void onLowMemory() {}

  @Override
  public void onTrimMemory(int level) {}

  @Override
  public void onTerminate() {}
}
