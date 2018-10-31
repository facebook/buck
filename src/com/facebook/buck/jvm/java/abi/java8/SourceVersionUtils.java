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

package com.facebook.buck.jvm.java.abi;

import javax.lang.model.SourceVersion;
import org.objectweb.asm.Opcodes;

/** Utility methods for working with source versions. */
public class SourceVersionUtils {
  /** Gets the class file version corresponding to the given source version constant. */
  public static int sourceVersionToClassFileVersion(SourceVersion version) {
    switch (version) {
      case RELEASE_0:
        return Opcodes.V1_1; // JVMS8 4.1: 1.0 and 1.1 both support version 45.3 (Opcodes.V1_1)
      case RELEASE_1:
        return Opcodes.V1_1;
      case RELEASE_2:
        return Opcodes.V1_2;
      case RELEASE_3:
        return Opcodes.V1_3;
      case RELEASE_4:
        return Opcodes.V1_4;
      case RELEASE_5:
        return Opcodes.V1_5;
      case RELEASE_6:
        return Opcodes.V1_6;
      case RELEASE_7:
        return Opcodes.V1_7;
      case RELEASE_8:
        return Opcodes.V1_8;
      default:
        throw new IllegalArgumentException(String.format("Unexpected source version: %s", version));
    }
  }
}
