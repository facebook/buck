// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.errors;

/**
 * Exception to signal an unexpected state internally to the compiler.
 *
 * Exceptions of this type always represent an bug in the compiler.
 * For expected errors, such as invalid input, the compiler should generate a CompilationError.
  */
public class InternalCompilerError extends IllegalStateException {

  public InternalCompilerError() {
  }

  public InternalCompilerError(String message) {
    super(message);
  }

  public InternalCompilerError(String message, Throwable cause) {
    super(message, cause);
  }

  public InternalCompilerError(Throwable cause) {
    super(cause);
  }
}
