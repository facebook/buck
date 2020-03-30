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
import com.google.common.base.Preconditions;

/**
 * Class to store paths which are in out/outs argument of genrule. Checks if the paths are forward,
 * relative, non empty and simple.
 */
public class GenruleOutPath {
  private String path;

  private GenruleOutPath(String s) {
    this.path = s;
  }

  /**
   * Creates a GenruleOut object from a string. Uses assertValid to verify and returns a
   * HumanReadableException.
   */
  public static GenruleOutPath of(String s) {
    try {
      assertValid(s);
    } catch (RuntimeException e) {
      throw new HumanReadableException(
          String.format(
              "'%s' is not a valid parameter of 'out' or 'outs' field in genrule. The output path must be relative, simple, non-empty and not cross package boundary.",
              s));
    }
    return new GenruleOutPath(s);
  }

  /**
   * Don't allow empty paths, absolute paths, paths ending with / or paths containing "."/".." Only
   * exception is when out = "."
   */
  public static void assertValid(String s) {
    Preconditions.checkArgument(!s.isEmpty());
    // Lets allow ./ for now as it occurs in a bunch of places
    if (s.equals(".") || s.equals("./")) {
      return;
    }
    ForwardRelativePath.of(s);
  }

  @Override
  public String toString() {
    return path;
  }

  public static String stringMapper(GenruleOutPath p) {
    return p.toString();
  }
}
