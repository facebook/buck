// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

/**
 * Exception to signal an compilation error.
 *
 * This is always an expected error and considered a user input issue.
 * A user-understandable message must be provided.
 */
public class CompilationException extends Exception {
  private static final long serialVersionUID = 1L;

  /**
   * Construct the exception with a {@link String} message.
   * @param message the message
   */
  public CompilationException(String message) {
    super(message);
  }

  /**
   * Construct the exception with a {@link String} message and a {@link Throwable} cause.
   * @param message the message
   * @param cause the cause
   */
  public CompilationException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Construct the exception with a {@link Throwable} cause.
   * @param cause the cause
   */
  public CompilationException(Throwable cause) {
    super(cause.getMessage(), cause);
  }

  protected CompilationException() {
    super();
  }

  public String getMessageForD8() {
    return super.getMessage();
  }

  public String getMessageForR8() {
    return super.getMessage();
  }
}

