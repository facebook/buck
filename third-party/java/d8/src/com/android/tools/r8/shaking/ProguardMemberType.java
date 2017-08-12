// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

public enum ProguardMemberType {
  // Please keep the order so fields are before methods
  FIELD,
  ALL_FIELDS,
  ALL,
  ALL_METHODS,
  INIT,
  CONSTRUCTOR,
  METHOD;

  public boolean includesFields() {
    return ordinal() <= ALL.ordinal();
  }

  public boolean includesMethods() {
    return ordinal() >= ALL.ordinal();
  }
}
