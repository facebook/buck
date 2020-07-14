// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.packages;

/**
 * Metadata of a rule attribute. Contains the attribute name and type, and an default value to be
 * used if none is provided in a rule declaration in a BUILD file. Attributes are immutable, and may
 * be shared by more than one rule (for example, <code>foo_binary</code> and <code>foo_library
 * </code> may share many attributes in common).
 */
public final class Attribute {

  /**
   * Returns if an attribute with the given name is an implicit dependency according to the
   * naming policy that designates implicit attributes.
   */
  public static boolean isImplicit(String name) {
    return name.startsWith("$");
  }

  /**
   * Returns if an attribute with the given name is late-bound according to the naming policy
   * that designates late-bound attributes.
   */
  public static boolean isLateBound(String name) {
    return name.startsWith(":");
  }

  /** Returns whether this attribute is considered private in Skylark. */
  private static boolean isPrivateAttribute(String nativeAttrName) {
    return isLateBound(nativeAttrName) || isImplicit(nativeAttrName);
  }

  /**
   * Returns the Skylark-usable name of this attribute.
   *
   * Implicit and late-bound attributes start with '_' (instead of '$' or ':').
   */
  public static String getSkylarkName(String nativeAttrName) {
    if (isPrivateAttribute(nativeAttrName)) {
      return "_" + nativeAttrName.substring(1);
    }
    return nativeAttrName;
  }
}
