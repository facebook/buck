// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.graph.ObjectToOffsetMapping;
import com.android.tools.r8.naming.ClassNameMapper;
import java.nio.ShortBuffer;

abstract class Format10x extends Base1Format {

  // øø | op
  Format10x(int high, BytecodeStream stream) {
    // Intentionally left empty.
    super(stream);
  }

  protected Format10x() {
  }

  @Override
  public void write(ShortBuffer dest, ObjectToOffsetMapping mapping) {
    writeFirst(0, dest);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }

  @Override
  public boolean equals(Object other) {
    return (other != null) && (this.getClass() == other.getClass());
  }

  @Override
  public String toString(ClassNameMapper naming) {
    return formatString(null);
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    return formatSmaliString(null);
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    // No references.
  }
}
