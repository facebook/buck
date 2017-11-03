// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.dex.MixedSectionCollection;
import java.util.Arrays;
import java.util.List;

public class DexDebugInfo extends CachedHashValueDexItem {

  public final int startLine;
  public final DexString[] parameters;
  public DexDebugEvent[] events;

  public DexDebugInfo(int startLine, DexString[] parameters, DexDebugEvent[] events) {
    assert startLine >= 0;
    this.startLine = startLine;
    this.parameters = parameters;
    this.events = events;
    // This call to hashCode is just an optimization to speedup equality when
    // canonicalizing DexDebugInfo objects inside a synchronize method.
    hashCode();
  }

  public List<DexDebugEntry> computeEntries() {
    DexDebugEntryBuilder builder = new DexDebugEntryBuilder(startLine);
    for (DexDebugEvent event : events) {
      event.addToBuilder(builder);
    }
    return builder.build();
  }

  @Override
  public int computeHashCode() {
    return startLine
        + Arrays.hashCode(parameters) * 7
        + Arrays.hashCode(events) * 13;
  }

  @Override
  public boolean computeEquals(Object other) {
    if (other instanceof DexDebugInfo) {
      DexDebugInfo o = (DexDebugInfo) other;
      if (startLine != o.startLine) {
        return false;
      }
      if (!Arrays.equals(parameters, o.parameters)) {
        return false;
      }
      return Arrays.equals(events, o.events);
    }
    return false;
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection collection) {
    collectAll(collection, parameters);
    collectAll(collection, events);
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection collection) {
    collection.add(this);
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("DebugInfo (line " + startLine + ") events: [\n");
    for (DexDebugEvent event : events) {
      builder.append("  ").append(event).append("\n");
    }
    builder.append("  END_SEQUENCE\n");
    builder.append("]\n");
    return builder.toString();
  }
}
