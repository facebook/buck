// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import java.util.Set;

public class ProguardCheckDiscardRule extends ProguardConfigurationRule {

  public static class Builder extends ProguardConfigurationRule.Builder {

    private Builder() {
    }

    public ProguardCheckDiscardRule build() {
      return new ProguardCheckDiscardRule(classAnnotation, classAccessFlags,
          negatedClassAccessFlags, classTypeNegated, classType, classNames, inheritanceAnnotation,
          inheritanceClassName, inheritanceIsExtends, memberRules);
    }
  }

  private ProguardCheckDiscardRule(
      ProguardTypeMatcher classAnnotation,
      ProguardAccessFlags classAccessFlags,
      ProguardAccessFlags negatedClassAccessFlags,
      boolean classTypeNegated,
      ProguardClassType classType,
      ProguardClassNameList classNames,
      ProguardTypeMatcher inheritanceAnnotation,
      ProguardTypeMatcher inheritanceClassName,
      boolean inheritanceIsExtends,
      Set<ProguardMemberRule> memberRules) {
    super(classAnnotation, classAccessFlags, negatedClassAccessFlags, classTypeNegated, classType,
        classNames, inheritanceAnnotation, inheritanceClassName, inheritanceIsExtends, memberRules);
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  String typeString() {
    return "checkdiscard";
  }
}
