// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.Constants;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.function.BooleanSupplier;

public class ClassAccessFlags extends AccessFlags {

  // List of valid flags for both DEX and Java.
  private static final int SHARED_FLAGS
      = AccessFlags.BASE_FLAGS
      | Constants.ACC_INTERFACE
      | Constants.ACC_ABSTRACT
      | Constants.ACC_ANNOTATION
      | Constants.ACC_ENUM;

  private static final int DEX_FLAGS
      = SHARED_FLAGS;

  private static final int CF_FLAGS
      = SHARED_FLAGS
      | Constants.ACC_SUPER;

  @Override
  protected List<String> getNames() {
    return new ImmutableList.Builder<String>()
        .addAll(super.getNames())
        .add("interface")
        .add("abstract")
        .add("annotation")
        .add("enum")
        .build();
  }

  @Override
  protected List<BooleanSupplier> getPredicates() {
    return new ImmutableList.Builder<BooleanSupplier>()
        .addAll(super.getPredicates())
        .add(this::isInterface)
        .add(this::isAbstract)
        .add(this::isAnnotation)
        .add(this::isEnum)
        .build();
  }

  private ClassAccessFlags(int flags) {
    super(flags);
  }

  public static ClassAccessFlags fromSharedAccessFlags(int access) {
    assert (access & SHARED_FLAGS) == access;
    assert SHARED_FLAGS == DEX_FLAGS;
    return fromDexAccessFlags(access);
  }

  public static ClassAccessFlags fromDexAccessFlags(int access) {
    // Assume that the SUPER flag should be set (behaviour for Java versions > 1.1).
    return new ClassAccessFlags((access & DEX_FLAGS) | Constants.ACC_SUPER);
  }

  public static ClassAccessFlags fromCfAccessFlags(int access) {
    return new ClassAccessFlags(access & CF_FLAGS);
  }

  public ClassAccessFlags copy() {
    return new ClassAccessFlags(flags);
  }

  @Override
  public int getAsDexAccessFlags() {
    return flags & ~Constants.ACC_SUPER;
  }

  @Override
  public int getAsCfAccessFlags() {
    return flags;
  }

  public boolean isInterface() {
    return isSet(Constants.ACC_INTERFACE);
  }

  public void setInterface() {
    set(Constants.ACC_INTERFACE);
  }

  public void unsetInterface() {
    unset(Constants.ACC_INTERFACE);
  }

  public boolean isAbstract() {
    return isSet(Constants.ACC_ABSTRACT);
  }

  public void setAbstract() {
    set(Constants.ACC_ABSTRACT);
  }

  public void unsetAbstract() {
    unset(Constants.ACC_ABSTRACT);
  }

  public boolean isAnnotation() {
    return isSet(Constants.ACC_ANNOTATION);
  }

  public void setAnnotation() {
    set(Constants.ACC_ANNOTATION);
  }

  public boolean isEnum() {
    return isSet(Constants.ACC_ENUM);
  }

  public void setEnum() {
    set(Constants.ACC_ENUM);
  }

  public boolean isSuper() {
    return isSet(Constants.ACC_SUPER);
  }

  public void setSuper() {
    set(Constants.ACC_SUPER);
  }

  public void unsetSuper() {
    unset(Constants.ACC_SUPER);
  }
}
