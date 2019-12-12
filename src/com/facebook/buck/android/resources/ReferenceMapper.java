/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android.resources;

import java.nio.IntBuffer;

/**
 * A ReferenceMapper is used to reassign ids in Android's .arsc/.xml files.
 *
 * <p>Android .arsc/.xml files include many resource references. These are ints of the form
 * 0xPPTTIIII encoding three things: package, type, index. The index part is an index into an array
 * of integers (or rather, into multiple arrays of integers).
 *
 * <p>A ReferenceMapper implements a method to update references and to rewrite those arrays that
 * they refer to.
 */
public interface ReferenceMapper {
  /** Converts an id to its new value under this mapping. */
  int map(int id);

  /**
   * Given an IntBuffer with an entry for every value of the given type, rearranges the entries in
   * the buffer to match the id reassignment.
   */
  void rewrite(int type, IntBuffer buf);
}
