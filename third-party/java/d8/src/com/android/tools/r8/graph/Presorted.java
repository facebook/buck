// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

/**
 * Interface for capturing presorted behavior for Dex items.
 */
public interface Presorted {

  void setSortedIndex(int sortedIndex);

  int getSortedIndex();

  int sortedCompareTo(int other);
}
