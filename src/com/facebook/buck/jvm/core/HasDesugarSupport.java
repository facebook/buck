/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.jvm.core;

/**
 * Implemented by build rules that support desugar process to the lower java versions, for example
 * java 8 to java 7 desugar for Android builds.
 */
public interface HasDesugarSupport {

  /**
   * Desugar support for java 8 features withing single class file.
   *
   * <p>Such as Lambda expressions, Method references, Repeating annotations
   */
  default boolean isDesugarEnabled() {
    return false;
  }

  /** Desugar support for interface default and static methods. */
  default boolean isInterfaceMethodsDesugarEnabled() {
    return false;
  }
}
