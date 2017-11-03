// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.naming.signature;

/**
 * Actions triggered by the generic signature parser.
 */
public interface GenericSignatureAction<T> {

  public void parsedSymbol(char symbol);

  public void parsedIdentifier(String identifier);

  public T parsedTypeName(String name);

  public T parsedInnerTypeName(T enclosingType, String name);

  public void start();

  public void stop();
}
