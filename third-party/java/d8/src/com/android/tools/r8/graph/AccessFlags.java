// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.Constants;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Access flags common to classes, methods and fields. */
public abstract class AccessFlags {

  protected static final int BASE_FLAGS
      = Constants.ACC_PUBLIC
      | Constants.ACC_PRIVATE
      | Constants.ACC_PROTECTED
      | Constants.ACC_STATIC
      | Constants.ACC_FINAL
      | Constants.ACC_SYNTHETIC;

  // Ordered list of flag names. Must be consistent with getPredicates.
  private static final List<String> NAMES = ImmutableList.of(
      "public",
      "private",
      "protected",
      "static",
      "final",
      "synthetic"
  );

  // Get ordered list of flag predicates. Must be consistent with getNames.
  protected List<BooleanSupplier> getPredicates() {
    return ImmutableList.of(
        this::isPublic,
        this::isPrivate,
        this::isProtected,
        this::isStatic,
        this::isFinal,
        this::isSynthetic);
  }

  // Get ordered list of flag names. Must be consistent with getPredicates.
  protected List<String> getNames() {
    return NAMES;
  }

  protected int flags;

  protected AccessFlags(int flags) {
    this.flags = flags;
  }

  public abstract int getAsCfAccessFlags();

  public abstract int getAsDexAccessFlags();

  @Override
  public boolean equals(Object other) {
    if (other instanceof AccessFlags) {
      return flags == ((AccessFlags) other).flags;
    }
    return false;
  }

  @Override
  public int hashCode() {
    return flags;
  }

  public boolean isPublic() {
    return isSet(Constants.ACC_PUBLIC);
  }

  public void setPublic() {
    assert !isPrivate() && !isProtected();
    set(Constants.ACC_PUBLIC);
  }

  public void unsetPublic() {
    unset(Constants.ACC_PUBLIC);
  }

  public boolean isPrivate() {
    return isSet(Constants.ACC_PRIVATE);
  }

  public void setPrivate() {
    assert !isPublic() && !isProtected();
    set(Constants.ACC_PRIVATE);
  }

  public void unsetPrivate() {
    unset(Constants.ACC_PRIVATE);
  }

  public boolean isProtected() {
    return isSet(Constants.ACC_PROTECTED);
  }

  public void setProtected() {
    assert !isPublic() && !isPrivate();
    set(Constants.ACC_PROTECTED);
  }

  public void unsetProtected() {
    unset(Constants.ACC_PROTECTED);
  }

  public boolean isStatic() {
    return isSet(Constants.ACC_STATIC);
  }

  public void setStatic() {
    set(Constants.ACC_STATIC);
  }

  public boolean isFinal() {
    return isSet(Constants.ACC_FINAL);
  }

  public void setFinal() {
    set(Constants.ACC_FINAL);
  }

  public void unsetFinal() {
    unset(Constants.ACC_FINAL);
  }

  public boolean isSynthetic() {
    return isSet(Constants.ACC_SYNTHETIC);
  }

  public void setSynthetic() {
    set(Constants.ACC_SYNTHETIC);
  }

  public void unsetSynthetic() {
    unset(Constants.ACC_SYNTHETIC);
  }

  public void promoteNonPrivateToPublic() {
    if (!isPrivate()) {
      unsetProtected();
      setPublic();
    }
  }

  public void promoteToPublic() {
    unsetProtected();
    unsetPrivate();
    setPublic();
  }

  protected boolean isSet(int flag) {
    return (flags & flag) != 0;
  }

  protected void set(int flag) {
    flags |= flag;
  }

  protected void unset(int flag) {
    flags &= ~flag;
  }


  public String toSmaliString() {
    return toString();
  }

  @Override
  public String toString() {
    List<String> names = getNames();
    List<BooleanSupplier> predicates = getPredicates();
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < names.size(); i++) {
      if (predicates.get(i).getAsBoolean()) {
        if (builder.length() > 0) {
          builder.append(' ');
        }
        builder.append(names.get(i));
      }
    }
    return builder.toString();
  }
}
