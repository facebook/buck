// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.regalloc;

import static com.android.tools.r8.dex.Constants.U16BIT_MAX;

public class LiveIntervalsUse implements Comparable<LiveIntervalsUse> {
  private final int position;
  private final int limit;

  public LiveIntervalsUse(int position, int limit) {
    this.position = position;
    this.limit = limit;
  }

  public int getPosition() {
    return position;
  }

  public int getLimit() {
    return limit;
  }

  @Override
  public int hashCode() {
    return position + limit * 7;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof LiveIntervalsUse)) {
      return false;
    }
    LiveIntervalsUse o = (LiveIntervalsUse) other;
    return o.position == position && o.limit == limit;
  }

  @Override
  public int compareTo(LiveIntervalsUse o) {
    if (o.position != position) {
      return position - o.position;
    }
    return limit - o.limit;
  }

  public boolean hasConstraint() {
    return limit < U16BIT_MAX;
  }
}
