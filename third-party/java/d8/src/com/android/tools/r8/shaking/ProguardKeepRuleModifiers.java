// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

public class ProguardKeepRuleModifiers {
  public static class Builder {
    public boolean allowsShrinking = false;
    public boolean allowsOptimization = false;
    public boolean allowsObfuscation = false;
    public boolean includeDescriptorClasses = false;

    private Builder() {}

    ProguardKeepRuleModifiers build() {
      return new ProguardKeepRuleModifiers(allowsShrinking, allowsOptimization, allowsObfuscation,
          includeDescriptorClasses);
    }
  }

  public final boolean allowsShrinking;
  public final boolean allowsOptimization;
  public final boolean allowsObfuscation;
  public final boolean includeDescriptorClasses;

  private ProguardKeepRuleModifiers(
      boolean allowsShrinking,
      boolean allowsOptimization,
      boolean allowsObfuscation,
      boolean includeDescriptorClasses) {
    this.allowsShrinking = allowsShrinking;
    this.allowsOptimization = allowsOptimization;
    this.allowsObfuscation = allowsObfuscation;
    this.includeDescriptorClasses = includeDescriptorClasses;
  }
  /**
   * Create a new empty builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof ProguardKeepRuleModifiers)) {
      return false;
    }
    ProguardKeepRuleModifiers that = (ProguardKeepRuleModifiers) o;

    return allowsShrinking == that.allowsShrinking
        && allowsOptimization == that.allowsOptimization
        && allowsObfuscation == that.allowsObfuscation
        && includeDescriptorClasses == that.includeDescriptorClasses;
  }

  @Override
  public int hashCode() {
    return (allowsShrinking ? 1 : 0)
        | (allowsOptimization ? 2 : 0)
        | (allowsObfuscation ? 4 : 0)
        | (includeDescriptorClasses ? 8 : 0);
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    appendWithComma(builder, allowsObfuscation, "allowobfuscation");
    appendWithComma(builder, allowsShrinking, "allowshrinking");
    appendWithComma(builder, allowsOptimization, "allowoptimization");
    appendWithComma(builder, includeDescriptorClasses, "includedescriptorclasses");
    return builder.toString();
  }

  private void appendWithComma(StringBuilder builder, boolean predicate,
      String text) {
    if (!predicate) {
      return;
    }
    if (builder.length() != 0) {
      builder.append(',');
    }
    builder.append(text);
  }
}
