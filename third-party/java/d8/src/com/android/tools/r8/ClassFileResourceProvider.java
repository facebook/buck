// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import java.util.Set;

/**
 * Represents a provider for application resources of class file kind.
 *
 * Note that the classes will only be created for resources provided by
 * resource providers on-demand when they are needed by the tool. If
 * never needed, the resource will never be loaded.
 */
public interface ClassFileResourceProvider {
  /** Returns all class descriptors. */
  Set<String> getClassDescriptors();

  /**
   * Get the class resource associated with the descriptor, or null if
   * this provider does not have one.
   *
   * Method may be called several times for the same resource, and should
   * support concurrent calls from different threads.
   */
  Resource getResource(String descriptor);
}
