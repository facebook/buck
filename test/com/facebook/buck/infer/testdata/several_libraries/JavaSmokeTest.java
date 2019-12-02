package com.facebook.buck.infer.testdata.several_libraries;

public class JavaSmokeTest {
  public String warnReturnNullableFromNonnullable() {
    L1 o = new L2().getL1();
    return o.getNullableString();
  }
}
