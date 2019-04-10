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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiParameterized;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.stream.Collectors;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.VariableElement;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiParameterized.class)
public class TreeBackedVariableElementTest extends CompilerTreeApiParameterizedTest {
  @Test
  public void testGetConstantValue() throws IOException {
    compile(
        Joiner.on('\n')
            .join("public class Foo {", "  public static final int CONSTANT = 42;", "}"));

    VariableElement variable = findField("CONSTANT", elements.getTypeElement("Foo"));

    assertEquals(42, variable.getConstantValue());
  }

  @Test
  public void testGetConstantValueDelegatedValueMissing() throws IOException {
    withClasspath(
        ImmutableMap.of(
            "Bar.java",
            Joiner.on('\n')
                .join("public class Bar {", "  public static final int CONSTANT = 42;", "}")));

    compile(
        Joiner.on('\n')
            .join("public class Foo {", "  public static final int CONSTANT = Bar.CONSTANT;", "}"));

    VariableElement variable = findField("CONSTANT", elements.getTypeElement("Foo"));

    if (testingJavac()) {
      assertEquals(42, variable.getConstantValue());
    } else {
      assertNull(variable.getConstantValue());
    }
    assertThat(testCompiler.getDiagnosticMessages(), Matchers.empty());
  }

  @Test
  public void testGetConstantValueComplexValue() throws IOException {
    compile(
        Joiner.on('\n')
            .join(
                "public class Foo {",
                "  private static final int A = 40;",
                "  private static final int B = 2;",
                "  public static final int CONSTANT = A + B;",
                "}"));

    VariableElement variable = findField("CONSTANT", elements.getTypeElement("Foo"));

    assertEquals(42, variable.getConstantValue());
  }

  @Test
  public void testGetConstantValueComplexValueMissing() throws IOException {
    withClasspath(
        ImmutableMap.of(
            "Bar.java",
            Joiner.on('\n')
                .join("public class Bar {", "  public static final boolean ADD = true;", "}")));

    compile(
        Joiner.on('\n')
            .join(
                "public class Foo {",
                "  private static final boolean ADD = Bar.ADD;",
                "  private static final int A = 40;",
                "  private static final int B = 2;",
                "  public static final int CONSTANT = ADD ? A + B : 42;",
                "}"));

    VariableElement variable = findField("CONSTANT", elements.getTypeElement("Foo"));

    if (testingJavac()) {
      assertEquals(42, variable.getConstantValue());
    } else {
      assertNull(variable.getConstantValue());
    }
    assertThat(testCompiler.getDiagnosticMessages(), Matchers.empty());
  }

  @Test
  public void testGetAnnotationFromField() throws IOException {
    compile(Joiner.on('\n').join("public class Foo {", "  @Deprecated int field;", "}"));

    VariableElement variable = findField("field", elements.getTypeElement("Foo"));
    assertThat(
        variable.getAnnotationMirrors().stream()
            .map(AnnotationMirror::toString)
            .collect(Collectors.toList()),
        Matchers.contains("@java.lang.Deprecated"));
  }

  @Test
  public void testGetAnnotationFromParameter() throws IOException {
    compile(
        Joiner.on('\n')
            .join("public class Foo {", "  public void foo(@Deprecated int parameter) { }", "}"));

    VariableElement variable =
        findParameter("parameter", findMethod("foo", elements.getTypeElement("Foo")));
    assertThat(
        variable.getAnnotationMirrors().stream()
            .map(AnnotationMirror::toString)
            .collect(Collectors.toList()),
        Matchers.contains("@java.lang.Deprecated"));
  }
}
