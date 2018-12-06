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

import com.facebook.buck.core.exceptions.HumanReadableException;
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
      case RELEASE_9:
        return Opcodes.V9;
      case RELEASE_10:
        return Opcodes.V10;
      default:
        throw new IllegalArgumentException(String.format("Unexpected source version: %s", version));
    }
  }

  /** Gets the source version corresponding to the given target string. */
  public static SourceVersion getSourceVersionFromTarget(String target) {
    switch (target) {
      case "1.3":
        return SourceVersion.RELEASE_3;
      case "1.4":
        return SourceVersion.RELEASE_4;
      case "1.5":
      case "5":
        return SourceVersion.RELEASE_5;
      case "1.6":
      case "6":
        return SourceVersion.RELEASE_6;
      case "1.7":
      case "7":
        return SourceVersion.RELEASE_7;
      case "1.8":
      case "8":
        return SourceVersion.RELEASE_8;
      case "9":
        return SourceVersion.RELEASE_9;
      case "10":
        return SourceVersion.RELEASE_10;
      default:
        throw new HumanReadableException("target %s not supported", target);
    }
  }
}
