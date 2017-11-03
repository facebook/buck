// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.AccessFlags;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.function.BooleanSupplier;

public class ProguardAccessFlags {

  private int flags = 0;

  // Ordered list of flag names. Must be consistent with getPredicates.
  private static final List<String> NAMES = ImmutableList.of(
      "public",
      "private",
      "protected",
      "static",
      "final",
      "abstract",
      "volatile",
      "transient",
      "synchronized",
      "native",
      "strictfp"
  );

  // Get ordered list of flag predicates. Must be consistent with getNames.
  private List<BooleanSupplier> getPredicates() {
    return ImmutableList.of(
        this::isPublic,
        this::isPrivate,
        this::isProtected,
        this::isStatic,
        this::isFinal,
        this::isAbstract,
        this::isVolatile,
        this::isTransient,
        this::isSynchronized,
        this::isNative,
        this::isStrict);
  }

  private boolean containsAll(int other) {
    return (flags & other) == flags;
  }

  private boolean containsNone(int other) {
    return (flags & other) == 0;
  }

  public boolean containsAll(AccessFlags other) {
    return containsAll(other.getAsCfAccessFlags());
  }

  public boolean containsNone(AccessFlags other) {
    return containsNone(other.getAsCfAccessFlags());
  }

  public void setPublic() {
    set(Constants.ACC_PUBLIC);
  }

  public boolean isPublic() {
    return isSet(Constants.ACC_PUBLIC);
  }

  public void setPrivate() {
    set(Constants.ACC_PRIVATE);
  }

  public boolean isPrivate() {
    return isSet(Constants.ACC_PRIVATE);
  }

  public void setProtected() {
    set(Constants.ACC_PROTECTED);
  }

  public boolean isProtected() {
    return isSet(Constants.ACC_PROTECTED);
  }

  public void setStatic() {
    set(Constants.ACC_STATIC);
  }

  public boolean isStatic() {
    return isSet(Constants.ACC_STATIC);
  }

  public void setFinal() {
    set(Constants.ACC_FINAL);
  }

  public boolean isFinal() {
    return isSet(Constants.ACC_FINAL);
  }

  public void setAbstract() {
    set(Constants.ACC_ABSTRACT);
  }

  public boolean isAbstract() {
    return isSet(Constants.ACC_ABSTRACT);
  }

  public void setVolatile() {
    set(Constants.ACC_VOLATILE);
  }

  public boolean isVolatile() {
    return isSet(Constants.ACC_VOLATILE);
  }

  public void setTransient() {
    set(Constants.ACC_TRANSIENT);
  }

  public boolean isTransient() {
    return isSet(Constants.ACC_TRANSIENT);
  }

  public void setSynchronized() {
    set(Constants.ACC_SYNCHRONIZED);
  }

  public boolean isSynchronized() {
    return isSet(Constants.ACC_SYNCHRONIZED);
  }

  public void setNative() {
    set(Constants.ACC_NATIVE);
  }

  public boolean isNative() {
    return isSet(Constants.ACC_NATIVE);
  }

  public void setStrict() {
    set(Constants.ACC_STRICT);
  }

  public boolean isStrict() {
    return isSet(Constants.ACC_STRICT);
  }

  private boolean isSet(int flag) {
    return (flags & flag) != 0;
  }

  private void set(int flag) {
    flags |= flag;
  }

  @Override
  public String toString() {
    List<BooleanSupplier> predicates = getPredicates();
    StringBuilder builder = new StringBuilder();
    boolean space = false;
    for (int i = 0; i < NAMES.size(); i++) {
      if (predicates.get(i).getAsBoolean()) {
        if (space) {
          builder.append(' ');
        } else {
          space = true;
        }
        builder.append(NAMES.get(i));
      }
    }
    return builder.toString();
  }
}
