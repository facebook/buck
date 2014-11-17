/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.io;

import java.io.IOException;

public interface DirectoryTraverser {

  /**
   * Takes a {@link DirectoryTraversal} and ensures that
   * {@link DirectoryTraversal#visit(java.io.File, String)} is invoked, as appropriate. Normally,
   * this is handled by invoking the traversal's {@link DirectoryTraversal#traverse()} method,
   * though this extra level of abstraction makes it possible to inject different behavior for unit
   * tests.
   */
  public void traverse(DirectoryTraversal traversal) throws IOException;

}
