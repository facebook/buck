package com.facebook.buck.demo;

import com.facebook.buck.android.support.exopackage.ExopackageApplication;

public class WithExo extends ExopackageApplication {

  public WithExo() {
    super(com.facebook.buck.demo.BuildConfig.EXOPACKAGE_FLAGS);
  }
}
