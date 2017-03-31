/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.ide.intellij;

/**
 * Values for the xml attribute <code>type</code> for IntelliJ <code>*.iml</code>-style modules.
 */
public enum IjModuleType {
  /**
   * Both normal Java and android-flavor Java modules are of this type.
   */
  JAVA_MODULE,

  /**
   * Modules that contain IntelliJ plugins use this custom type to indicate
   * that they should be run in an environment with an IDEA installation.
   */
  PLUGIN_MODULE;

  /**
   * All IJ modules must have a module type.  If we don't know better,
   * arbitrarily choose java.
   */
  public static final IjModuleType DEFAULT = JAVA_MODULE;
}
