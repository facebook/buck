/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertEquals;

import com.google.common.base.Optional;

import org.junit.Test;

public class CxxSourceTest {

  @Test
  public void typesAreCorrectlyDetectedFromExtensions() {
    // C
    assertEquals(Optional.of(CxxSource.Type.C), CxxSource.Type.fromExtension("c"));

    // C++
    assertEquals(Optional.of(CxxSource.Type.CXX), CxxSource.Type.fromExtension("c++"));
    assertEquals(Optional.of(CxxSource.Type.CXX), CxxSource.Type.fromExtension("cc"));
    assertEquals(Optional.of(CxxSource.Type.CXX), CxxSource.Type.fromExtension("cp"));
    assertEquals(Optional.of(CxxSource.Type.CXX), CxxSource.Type.fromExtension("cxx"));
    assertEquals(Optional.of(CxxSource.Type.CXX), CxxSource.Type.fromExtension("cpp"));
    assertEquals(Optional.of(CxxSource.Type.CXX), CxxSource.Type.fromExtension("CPP"));
    assertEquals(Optional.of(CxxSource.Type.CXX), CxxSource.Type.fromExtension("C"));

    // Objective-C
    assertEquals(Optional.of(CxxSource.Type.OBJC), CxxSource.Type.fromExtension("m"));

    // Objective-C++
    assertEquals(Optional.of(CxxSource.Type.OBJCXX), CxxSource.Type.fromExtension("mm"));

    // Preprocessed C
    assertEquals(Optional.of(CxxSource.Type.C_CPP_OUTPUT), CxxSource.Type.fromExtension("i"));

    // Preprocessed C++
    assertEquals(Optional.of(CxxSource.Type.CXX_CPP_OUTPUT), CxxSource.Type.fromExtension("ii"));

    // Preprocessed Objective-C
    assertEquals(Optional.of(CxxSource.Type.OBJC_CPP_OUTPUT), CxxSource.Type.fromExtension("mi"));

    // Preprocessed Objective-C++
    assertEquals(
        Optional.of(CxxSource.Type.OBJCXX_CPP_OUTPUT),
        CxxSource.Type.fromExtension("mii"));

    // Assembly
    assertEquals(Optional.of(CxxSource.Type.ASSEMBLER), CxxSource.Type.fromExtension("s"));

    // Preprocessable assembly
    assertEquals(Optional.of(CxxSource.Type.ASSEMBLER_WITH_CPP), CxxSource.Type.fromExtension("S"));
  }

}
