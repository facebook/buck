// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

public abstract class Descriptor<T extends DexItem, S extends Descriptor<T,S>>
    extends IndexedDexItem implements PresortedComparable<S> {

  public abstract boolean match(T entry);

  public abstract DexType getHolder();
}
