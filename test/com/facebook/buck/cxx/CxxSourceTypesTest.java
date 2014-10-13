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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link CxxSourceTypes}.
 */
public class CxxSourceTypesTest {

  @Test
  public void expectedTypesArePreprocessable() {
    assertTrue(CxxSourceTypes.isPreprocessableType(CxxSource.Type.C));
    assertTrue(CxxSourceTypes.isPreprocessableType(CxxSource.Type.CXX));
    assertTrue(CxxSourceTypes.isPreprocessableType(CxxSource.Type.OBJC));
  }

  @Test
  public void expectedTypesAreNotPreprocessable() {
    assertFalse(CxxSourceTypes.isPreprocessableType(CxxSource.Type.ASSEMBLER));
  }

  @Test
  public void expectedTypesNeedCxxCompiler() {
    assertTrue(CxxSourceTypes.isPreprocessableType(CxxSource.Type.CXX));
    assertTrue(CxxSourceTypes.isPreprocessableType(CxxSource.Type.OBJCXX));
  }

  @Test
  public void expectedTypesDoNotNeedCxxCompiler() {
    assertTrue(CxxSourceTypes.isPreprocessableType(CxxSource.Type.C));
    assertTrue(CxxSourceTypes.isPreprocessableType(CxxSource.Type.OBJC));
  }
}
