// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.utils.StringUtils;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public abstract class ProguardClassSpecification {

  public static class Builder {

    protected ProguardTypeMatcher classAnnotation;
    protected ProguardAccessFlags classAccessFlags = new ProguardAccessFlags();
    protected ProguardAccessFlags negatedClassAccessFlags = new ProguardAccessFlags();
    protected boolean classTypeNegated = false;
    protected ProguardClassType classType;
    protected ProguardClassNameList classNames;
    protected ProguardTypeMatcher inheritanceAnnotation;
    protected ProguardTypeMatcher inheritanceClassName;
    protected boolean inheritanceIsExtends = false;
    protected Set<ProguardMemberRule> memberRules = new LinkedHashSet<>();

    protected Builder() {
    }

    public Set<ProguardMemberRule> getMemberRules() {
      return memberRules;
    }

    public void setMemberRules(Set<ProguardMemberRule> memberRules) {
      this.memberRules = memberRules;
    }

    public boolean getInheritanceIsExtends() {
      return inheritanceIsExtends;
    }

    public void setInheritanceIsExtends(boolean inheritanceIsExtends) {
      this.inheritanceIsExtends = inheritanceIsExtends;
    }

    public boolean hasInheritanceClassName() {
      return inheritanceClassName != null;
    }

    public ProguardTypeMatcher getInheritanceClassName() {
      return inheritanceClassName;
    }

    public void setInheritanceClassName(ProguardTypeMatcher inheritanceClassName) {
      this.inheritanceClassName = inheritanceClassName;
    }

    public ProguardTypeMatcher getInheritanceAnnotation() {
      return inheritanceAnnotation;
    }

    public void setInheritanceAnnotation(ProguardTypeMatcher inheritanceAnnotation) {
      this.inheritanceAnnotation = inheritanceAnnotation;
    }

    public ProguardClassNameList getClassNames() {
      return classNames;
    }

    public void setClassNames(ProguardClassNameList classNames) {
      this.classNames = classNames;
    }

    public ProguardClassType getClassType() {
      return classType;
    }

    public void setClassType(ProguardClassType classType) {
      this.classType = classType;
    }

    public boolean getClassTypeNegated() {
      return classTypeNegated;
    }

    public void setClassTypeNegated(boolean classTypeNegated) {
      this.classTypeNegated = classTypeNegated;
    }

    public ProguardAccessFlags getClassAccessFlags() {
      return classAccessFlags;
    }

    public void setClassAccessFlags(ProguardAccessFlags flags) {
      classAccessFlags = flags;
    }

    public ProguardAccessFlags getNegatedClassAccessFlags() {
      return negatedClassAccessFlags;
    }

    public void setNegatedClassAccessFlags(ProguardAccessFlags flags) {
      negatedClassAccessFlags = flags;
    }

    public ProguardTypeMatcher getClassAnnotation() {
      return classAnnotation;
    }

    public void setClassAnnotation(ProguardTypeMatcher classAnnotation) {
      this.classAnnotation = classAnnotation;
    }

    protected void matchAllSpecification() {
      setClassNames(ProguardClassNameList.singletonList(ProguardTypeMatcher.defaultAllMatcher()));
      setMemberRules(Collections.singleton(ProguardMemberRule.defaultKeepAllRule()));
    }
  }

  private final ProguardTypeMatcher classAnnotation;
  private final ProguardAccessFlags classAccessFlags;
  private final ProguardAccessFlags negatedClassAccessFlags;
  private final boolean classTypeNegated;
  private final ProguardClassType classType;
  private final ProguardClassNameList classNames;
  private final ProguardTypeMatcher inheritanceAnnotation;
  private final ProguardTypeMatcher inheritanceClassName;
  private final boolean inheritanceIsExtends;
  private final Set<ProguardMemberRule> memberRules;

  protected ProguardClassSpecification(
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
    this.classAnnotation = classAnnotation;
    this.classAccessFlags = classAccessFlags;
    this.negatedClassAccessFlags = negatedClassAccessFlags;
    this.classTypeNegated = classTypeNegated;
    this.classType = classType;
    this.classNames = classNames;
    this.inheritanceAnnotation = inheritanceAnnotation;
    this.inheritanceClassName = inheritanceClassName;
    this.inheritanceIsExtends = inheritanceIsExtends;
    this.memberRules = memberRules;
  }

  public Set<ProguardMemberRule> getMemberRules() {
    return memberRules;
  }

  public boolean getInheritanceIsExtends() {
    return inheritanceIsExtends;
  }

  public boolean hasInheritanceClassName() {
    return inheritanceClassName != null;
  }

  public ProguardTypeMatcher getInheritanceClassName() {
    return inheritanceClassName;
  }

  public ProguardTypeMatcher getInheritanceAnnotation() {
    return inheritanceAnnotation;
  }

  public ProguardClassNameList getClassNames() {
    return classNames;
  }

  public ProguardClassType getClassType() {
    return classType;
  }

  public boolean getClassTypeNegated() {
    return classTypeNegated;
  }

  public ProguardAccessFlags getClassAccessFlags() {
    return classAccessFlags;
  }

  public ProguardAccessFlags getNegatedClassAccessFlags() {
    return negatedClassAccessFlags;
  }

  public ProguardTypeMatcher getClassAnnotation() {
    return classAnnotation;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof ProguardClassSpecification)) {
      return false;
    }
    ProguardClassSpecification that = (ProguardClassSpecification) o;

    if (classTypeNegated != that.classTypeNegated) {
      return false;
    }
    if (inheritanceIsExtends != that.inheritanceIsExtends) {
      return false;
    }
    if (!Objects.equals(classAnnotation, that.classAnnotation)) {
      return false;
    }
    if (!classAccessFlags.equals(that.classAccessFlags)) {
      return false;
    }
    if (!negatedClassAccessFlags.equals(that.negatedClassAccessFlags)) {
      return false;
    }
    if (classType != that.classType) {
      return false;
    }
    if (!classNames.equals(that.classNames)) {
      return false;
    }
    if (!Objects.equals(inheritanceAnnotation, that.inheritanceAnnotation)) {
      return false;
    }
    if (!Objects.equals(inheritanceClassName, that.inheritanceClassName)) {
      return false;
    }
    return memberRules.equals(that.memberRules);
  }

  @Override
  public int hashCode() {
    // Used multiplier 3 to avoid too much overflow when computing hashCode.
    int result = (classAnnotation != null ? classAnnotation.hashCode() : 0);
    result = 3 * result + classAccessFlags.hashCode();
    result = 3 * result + negatedClassAccessFlags.hashCode();
    result = 3 * result + (classTypeNegated ? 1 : 0);
    result = 3 * result + (classType != null ? classType.hashCode() : 0);
    result = 3 * result + classNames.hashCode();
    result = 3 * result + (inheritanceAnnotation != null ? inheritanceAnnotation.hashCode() : 0);
    result = 3 * result + (inheritanceClassName != null ? inheritanceClassName.hashCode() : 0);
    result = 3 * result + (inheritanceIsExtends ? 1 : 0);
    result = 3 * result + memberRules.hashCode();
    return result;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    StringUtils.appendNonEmpty(builder, " @", classAnnotation, null);
    StringUtils.appendNonEmpty(builder, " ", classAccessFlags, null);
    StringUtils.appendNonEmpty(builder, " !", negatedClassAccessFlags.toString().replace(" ", " !"),
        null);
    if (builder.length() > 0) {
      builder.append(' ');
    }
    builder.append(classType);
    builder.append(' ');
    classNames.writeTo(builder);
    if (hasInheritanceClassName()) {
      builder.append(inheritanceIsExtends ? " extends" : " implements");
      StringUtils.appendNonEmpty(builder, " @", inheritanceAnnotation, null);
      builder.append(' ');
      builder.append(inheritanceClassName);
    }
    builder.append(" {\n");
    memberRules.forEach(memberRule -> {
      builder.append("  ");
      builder.append(memberRule);
      builder.append(";\n");
    });
    builder.append("}");
    return builder.toString();
  }
}
