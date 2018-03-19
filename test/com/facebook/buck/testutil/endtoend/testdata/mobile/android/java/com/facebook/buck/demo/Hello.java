/*
 * Copyright 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.buck.demo;

public class Hello {
  public Hello() {
    System.loadLibrary("jni");
  }

  public native String getHelloString();
}
