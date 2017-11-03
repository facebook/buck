// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils;

import com.android.tools.r8.dex.Constants;

public class NumberUtils {

  public static boolean is4Bit(long value) {
    return Constants.S4BIT_MIN <= value && value <= Constants.S4BIT_MAX;
  }

  public static boolean is8Bit(long value) {
    return Byte.MIN_VALUE <= value && value <= Byte.MAX_VALUE;
  }

  public static boolean negativeIs8Bit(long value) {
    return Byte.MIN_VALUE <= -value && -value <= Byte.MAX_VALUE;
  }

  public static boolean is16Bit(long value) {
    return Short.MIN_VALUE <= value && value <= Short.MAX_VALUE;
  }

  public static boolean negativeIs16Bit(long value) {
    return Short.MIN_VALUE <= -value && -value <= Short.MAX_VALUE;
  }

  public static boolean is32Bit(long value) {
    return Integer.MIN_VALUE <= value && value <= Integer.MAX_VALUE;
  }

  public static boolean isIntHigh16Bit(long value) {
    return (value & ~(0xffff << 16)) == 0;
  }

  public static boolean isLongHigh16Bit(long value) {
    return (value & ~(0xffffL << 48)) == 0;
  }
}
