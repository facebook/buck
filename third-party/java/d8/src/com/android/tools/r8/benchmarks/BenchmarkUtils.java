// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.benchmarks;

public class BenchmarkUtils {

  public static void printRuntimeNanoseconds(String name, double nano) {
    printRuntimeMilliseconds(name, nano / 1000000.0);
  }

  public static void printRuntimeMilliseconds(String name, double ms) {
    System.out.println(name + "(RunTime): " + ms + " ms");
  }
}
