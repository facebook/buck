package com.facebook.buck.infer.testdata.several_libraries;

import javax.annotation.Nullable;

public class L1 {
  public @Nullable String getNullableString() {
    return null;
  }

  public String acceptNullableString(@Nullable String string) {
    if (string != null) {
      return string;
    } else {
      return "Default";
    }
  }
}
