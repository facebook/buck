// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.regalloc;

class LiveRange {

  public final static LiveRange INFINITE = new LiveRange(0, Integer.MAX_VALUE);

  public int start;  // inclusive
  public int end;  // exclusive

  public LiveRange(int start, int end) {
    this.start = start;
    this.end = end;
  }

  @Override
  public String toString() {
    return "[" + start + ", " + end + "[";
  }

  public boolean isInfinite() {
    return this == INFINITE;
  }
}
