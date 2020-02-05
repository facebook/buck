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

package com.facebook.buck.core.path;

import com.facebook.buck.core.exceptions.HumanReadableException;
import org.junit.Assert;
import org.junit.Test;

public class GenruleOutPathTest {
  private boolean isValidOutPath(String s) {
    try {
      GenruleOutPath.of(s);
    } catch (HumanReadableException e) {
      return false;
    }
    return true;
  }

  @Test
  public void coercingInvalidPathThrowsException() {
    String[] invalidPaths = {"", "//a", "a/", "a/../b", "..", "a/./b", "./a", "/a/b"};
    for (String path : invalidPaths) {
      Assert.assertFalse(isValidOutPath(path));
    }
  }

  @Test
  public void coercingValidPath() {
    String[] validPaths = {".", "./", "a/b", "a/b/c", "a"};
    for (String path : validPaths) {
      Assert.assertTrue(isValidOutPath(path));
    }
  }
}
