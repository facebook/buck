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

package com.facebook.buck.jvm.java.abi.source;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.jvm.java.testutil.CompilerTreeApiParameterized;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

import javax.lang.model.type.TypeKind;

@RunWith(CompilerTreeApiParameterized.class)
public class StandalonePrimitiveTypeTest extends CompilerTreeApiParameterizedTest {
  @Test
  public void testToString() throws IOException {
    initCompiler();

    assertToString("boolean", TypeKind.BOOLEAN);
    assertToString("byte", TypeKind.BYTE);
    assertToString("char", TypeKind.CHAR);
    assertToString("short", TypeKind.SHORT);
    assertToString("int", TypeKind.INT);
    assertToString("long", TypeKind.LONG);
    assertToString("float", TypeKind.FLOAT);
    assertToString("double", TypeKind.DOUBLE);
  }

  private void assertToString(String expected, TypeKind typeKind) {
    assertEquals(expected, types.getPrimitiveType(typeKind).toString());
  }
}
