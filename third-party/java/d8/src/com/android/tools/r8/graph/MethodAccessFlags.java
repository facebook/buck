// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.Constants;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.function.BooleanSupplier;

public class MethodAccessFlags extends AccessFlags {

  private static final int SHARED_FLAGS
      = AccessFlags.BASE_FLAGS
      | Constants.ACC_SYNCHRONIZED
      | Constants.ACC_BRIDGE
      | Constants.ACC_VARARGS
      | Constants.ACC_NATIVE
      | Constants.ACC_ABSTRACT
      | Constants.ACC_STRICT;

  private static final int CF_FLAGS
      = SHARED_FLAGS;

  private static final int DEX_FLAGS
      = SHARED_FLAGS
      | Constants.ACC_CONSTRUCTOR
      | Constants.ACC_DECLARED_SYNCHRONIZED;

  @Override
  protected List<String> getNames() {
    return new ImmutableList.Builder<String>()
        .addAll(super.getNames())
        .add("synchronized")
        .add("bridge")
        .add("varargs")
        .add("native")
        .add("abstract")
        .add("strictfp")
        .build();
  }

  @Override
  protected List<BooleanSupplier> getPredicates() {
    return new ImmutableList.Builder<BooleanSupplier>()
        .addAll(super.getPredicates())
        .add(this::isSynchronized)
        .add(this::isBridge)
        .add(this::isVarargs)
        .add(this::isNative)
        .add(this::isAbstract)
        .add(this::isStrict)
        .build();
  }

  private MethodAccessFlags(int flags) {
    super(flags);
  }

  public MethodAccessFlags copy() {
    return new MethodAccessFlags(flags);
  }

  public static MethodAccessFlags fromSharedAccessFlags(int access, boolean isConstructor) {
    assert (access & SHARED_FLAGS) == access;
    assert CF_FLAGS == SHARED_FLAGS;
    return fromCfAccessFlags(access, isConstructor);
  }

  public static MethodAccessFlags fromCfAccessFlags(int access, boolean isConstructor) {
    return new MethodAccessFlags(
        (access & CF_FLAGS) | (isConstructor ? Constants.ACC_CONSTRUCTOR : 0));
  }

  public static MethodAccessFlags fromDexAccessFlags(int access) {
    MethodAccessFlags flags = new MethodAccessFlags(access & DEX_FLAGS);
    if (flags.isDeclaredSynchronized()) {
      flags.setSynchronized();
      flags.unsetDeclaredSynchronized();
    }
    return flags;
  }

  @Override
  public int getAsDexAccessFlags() {
    MethodAccessFlags copy = copy();
    if (copy.isSynchronized() && !copy.isNative()) {
      copy.unsetSynchronized();
      copy.setDeclaredSynchronized();
    }
    return copy.flags;
  }

  @Override
  public int getAsCfAccessFlags() {
    return flags & ~Constants.ACC_CONSTRUCTOR;
  }

  public boolean isSynchronized() {
    return isSet(Constants.ACC_SYNCHRONIZED);
  }

  public void setSynchronized() {
    set(Constants.ACC_SYNCHRONIZED);
  }

  public void unsetSynchronized() {
    unset(Constants.ACC_SYNCHRONIZED);
  }

  public boolean isBridge() {
    return isSet(Constants.ACC_BRIDGE);
  }

  public void setBridge() {
    set(Constants.ACC_BRIDGE);
  }

  public void unsetBridge() {
    unset(Constants.ACC_BRIDGE);
  }

  public boolean isVarargs() {
    return isSet(Constants.ACC_VARARGS);
  }

  public void setVarargs() {
    set(Constants.ACC_VARARGS);
  }

  public boolean isNative() {
    return isSet(Constants.ACC_NATIVE);
  }

  public void setNative() {
    set(Constants.ACC_NATIVE);
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

  public boolean isStrict() {
    return isSet(Constants.ACC_STRICT);
  }

  public void setStrict() {
    set(Constants.ACC_STRICT);
  }

  public boolean isConstructor() {
    return isSet(Constants.ACC_CONSTRUCTOR);
  }

  public void setConstructor() {
    set(Constants.ACC_CONSTRUCTOR);
  }

  public void unsetConstructor() {
    unset(Constants.ACC_CONSTRUCTOR);
  }

  // DEX only declared-synchronized flag.

  private boolean isDeclaredSynchronized() {
    return isSet(Constants.ACC_DECLARED_SYNCHRONIZED);
  }

  private void setDeclaredSynchronized() {
    set(Constants.ACC_DECLARED_SYNCHRONIZED);
  }

  private void unsetDeclaredSynchronized() {
    unset(Constants.ACC_DECLARED_SYNCHRONIZED);
  }
}
