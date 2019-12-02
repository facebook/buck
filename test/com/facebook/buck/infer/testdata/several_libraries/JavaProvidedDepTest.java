package com.facebook.buck.infer.testdata.several_libraries;

import javax.annotation.Nullable;

public class JavaProvidedDepTest {
  public String okPassNullableToNullableParam(@Nullable String name) {
    return new L1().acceptNullableString(name);
  }
}
