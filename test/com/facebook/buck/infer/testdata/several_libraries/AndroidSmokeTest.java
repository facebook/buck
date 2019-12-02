package com.facebook.buck.infer.testdata.several_libraries;

public class AndroidSmokeTest {
  public String warnReturnNullableFromExportedDep() {
    L1 o = new L1();
    return o.getNullableString();
  }
}
