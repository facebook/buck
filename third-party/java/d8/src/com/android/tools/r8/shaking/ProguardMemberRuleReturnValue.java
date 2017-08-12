// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.shaking;

import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.utils.LongInterval;

public class ProguardMemberRuleReturnValue {

  private final boolean booleanValue;
  private final LongInterval longInterval;
  private final DexField field;

  ProguardMemberRuleReturnValue(boolean value) {
    this.booleanValue = value;
    this.longInterval = null;
    this.field = null;
  }

  ProguardMemberRuleReturnValue(LongInterval value) {
    this.booleanValue = false;
    this.longInterval = value;
    this.field = null;
  }

  ProguardMemberRuleReturnValue(DexField field) {
    this.booleanValue = false;
    this.longInterval = null;
    this.field = field;
  }

  public boolean isBoolean() {
    return longInterval == null && field == null;
  }

  public boolean isValueRange() {
    return longInterval != null && field == null;
  }

  public boolean isField() {
    return field != null;
  }

  public boolean getBoolean() {
    assert isBoolean();
    return booleanValue;
  }

  /**
   * Returns if this return value is a single value.
   *
   * Boolean values are considered a single value.
   */
  public boolean isSingleValue() {
    return isBoolean() || (isValueRange() && longInterval.isSingleValue());
  }

  /**
   * Returns the return value.
   *
   * Boolean values are returned as 0 for <code>false</code> and 1 for <code>true</code>.
   */
  public long getSingleValue() {
    assert isSingleValue();
    if (isBoolean()) {
      return booleanValue ? 1 : 0;
    }
    return longInterval.getSingleValue();
  }

  public LongInterval getValueRange() {
    assert isValueRange();
    return longInterval;
  }

  public DexField getField() {
    assert isField();
    return field;
  }

  @Override
  public String toString() {
    StringBuilder result = new StringBuilder();
    result.append(" return ");
    if (isBoolean()) {
      result.append(booleanValue ? "true" : "false");
    } else if (isValueRange()) {
      result.append(longInterval.getMin());
      if (!isSingleValue()) {
        result.append("..");
        result.append(longInterval.getMax());
      }
    } else {
      assert isField();
      result.append(field.clazz.toSourceString() + '.' + field.name);
    }
    return result.toString();
  }
}
