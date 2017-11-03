// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

public interface BytecodeStream {

  /**
   * Returns the current position from the starting index in shorts.
   *
   * @return offset from start in shorts.
   */
  int getOffset();

  /**
   * Returns the next short value from the stream of values.
   *
   * @return next short value in stream.
   */
  int nextShort();

  /**
   * Returns the next byte value from the stream, i.e., the high value of the next short followed by
   * the low value.
   *
   * Both bytes need to be consumed before the next call to {@link #nextShort()}.
   *
   * @return next byte value in the stream.
   */
  int nextByte();

  /**
   * Returns true of there are more values to be consumed.
   *
   * @return true if more values can be consumed.
   */
  boolean hasMore();
}
