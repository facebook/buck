// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.errors;

/**
 * Exception to signal that a not-yet-implemented code path has been hit.
 */
public class Unimplemented extends RuntimeException {

  public Unimplemented() {
  }

  public Unimplemented(String message) {
    super(message);
  }
}
