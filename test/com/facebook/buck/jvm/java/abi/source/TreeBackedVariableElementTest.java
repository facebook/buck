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
import com.google.common.base.Joiner;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

import javax.lang.model.element.VariableElement;

@RunWith(CompilerTreeApiParameterized.class)
public class TreeBackedVariableElementTest extends CompilerTreeApiParameterizedTest {
  @Test
  public void testGetConstantValue() throws IOException {
    compile(Joiner.on('\n').join(
        "public class Foo {",
        "  public static final int CONSTANT = 42;",
        "}"));

    VariableElement variable = findField("CONSTANT", elements.getTypeElement("Foo"));

    assertEquals(42, variable.getConstantValue());
  }

  @Test
  public void testGetConstantValueComplexValue() throws IOException {
    compile(Joiner.on('\n').join(
        "public class Foo {",
        "  private static final int A = 40;",
        "  private static final int B = 2;",
        "  public static final int CONSTANT = A + B;",
        "}"));

    VariableElement variable = findField("CONSTANT", elements.getTypeElement("Foo"));

    assertEquals(42, variable.getConstantValue());
  }
}

