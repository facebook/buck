// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming;

import com.android.tools.r8.naming.MemberNaming.Signature;
import com.android.tools.r8.utils.ThrowingConsumer;

/**
 * Stores name information for a class.
 * <p>
 * Implementers will include how the class was renamed and information on the class's members.
 */
public interface ClassNaming {

  abstract class Builder {
    abstract Builder addMemberEntry(MemberNaming entry);
    abstract ClassNaming build();
  }

  MemberNaming lookup(Signature renamedSignature);

  MemberNaming lookupByOriginalSignature(Signature original);

  <T extends Throwable> void forAllMemberNaming(
      ThrowingConsumer<MemberNaming, T> consumer) throws T;

  <T extends Throwable> void forAllFieldNaming(
      ThrowingConsumer<MemberNaming, T> consumer) throws T;

  <T extends Throwable> void forAllMethodNaming(
      ThrowingConsumer<MemberNaming, T> consumer) throws T;
}
