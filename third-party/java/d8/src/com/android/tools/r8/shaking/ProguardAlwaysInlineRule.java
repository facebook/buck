// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import java.util.Set;

public class ProguardAlwaysInlineRule extends ProguardConfigurationRule {

  public static class Builder extends ProguardConfigurationRule.Builder {

    private Builder() {
    }

    public ProguardAlwaysInlineRule build() {
      return new ProguardAlwaysInlineRule(classAnnotation, classAccessFlags,
          negatedClassAccessFlags, classTypeNegated, classType, classNames, inheritanceAnnotation,
          inheritanceClassName, inheritanceIsExtends, memberRules);
    }
  }

  private ProguardAlwaysInlineRule(
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

  public static ProguardAlwaysInlineRule.Builder builder() {
    return new ProguardAlwaysInlineRule.Builder();
  }

  @Override
  String typeString() {
    return "alwaysinline";
  }
}
