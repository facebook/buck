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
package com.facebook.buck.jvm.java;

public enum JavacCompilationMode {
  /** Normal compilation. Generates full jars. */
  FULL,
  /**
   * Normal compilation with additional checking of type and constant references for compatibility
   * with generating ABI jars from source without dependencies. This mode issues warnings when
   * incompatibilities are detected and is thus intended for use during migration.
   */
  FULL_CHECKING_REFERENCES,
  /**
   * Normal compilation with additional checking of type and constant references for compatibility
   * with generating ABI jars from source wtihout dependencies. This mode issues errors when
   * incompatibilities are detected; it is the backstop that ensures the build will fail if an
   * incorrect ABI jar is generated.
   */
  FULL_ENFORCING_REFERENCES,
  /** Compile ABIs only. Generates an ABI jar. */
  ABI,
}
