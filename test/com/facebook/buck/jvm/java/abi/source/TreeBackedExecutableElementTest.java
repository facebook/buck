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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiParameterized;
import com.google.common.base.Joiner;
import java.io.IOException;
import java.util.stream.Collectors;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiParameterized.class)
public class TreeBackedExecutableElementTest extends CompilerTreeApiParameterizedTest {
  @Test
  public void testGetReturnType() throws IOException {
    compile(Joiner.on('\n').join("abstract class Foo {", "  public abstract String foo();", "}"));

    ExecutableElement element = findMethod("foo", elements.getTypeElement("Foo"));
    TypeMirror stringType = elements.getTypeElement("java.lang.String").asType();

    assertSameType(stringType, element.getReturnType());
  }

  @Test
  public void testGetVoidReturnType() throws IOException {
    compile(Joiner.on('\n').join("abstract class Foo {", "  public void foo();", "}"));

    ExecutableElement element = findMethod("foo", elements.getTypeElement("Foo"));
    TypeMirror voidType = types.getNoType(TypeKind.VOID);

    assertSameType(voidType, element.getReturnType());
  }

  @Test
  public void testGetConstructorReturnType() throws IOException {
    compile(Joiner.on('\n').join("class Foo {", "}"));

    ExecutableElement element = findDefaultConstructor(elements.getTypeElement("Foo"));
    TypeMirror voidType = types.getNoType(TypeKind.VOID);

    assertSameType(voidType, element.getReturnType());
  }

  @Test
  public void testGetParameters() throws IOException {
    compile(
        Joiner.on('\n')
            .join("abstract class Foo {", "  public abstract void foo(String s, int i);", "}"));

    VariableElement sParameter =
        findParameter("s", findMethod("foo", elements.getTypeElement("Foo")));
    VariableElement iParameter =
        findParameter("i", findMethod("foo", elements.getTypeElement("Foo")));

    assertSameType(elements.getTypeElement("java.lang.String").asType(), sParameter.asType());

    assertSameType(types.getPrimitiveType(TypeKind.INT), iParameter.asType());
  }

  @Test
  public void testNotVarArgs() throws IOException {
    compile(
        Joiner.on('\n')
            .join("abstract class Foo {", "  public abstract void foo(String[] strings);", "}"));

    TypeMirror stringArrayType =
        types.getArrayType(elements.getTypeElement("java.lang.String").asType());

    ExecutableElement method = findMethod("foo", elements.getTypeElement("Foo"));
    assertFalse(method.isVarArgs());
    assertSameType(stringArrayType, findParameter("strings", method).asType());
  }

  @Test
  public void testVarArgs() throws IOException {
    compile(
        Joiner.on('\n')
            .join("abstract class Foo {", "  public abstract void foo(String... strings);", "}"));

    TypeMirror stringArrayType =
        types.getArrayType(elements.getTypeElement("java.lang.String").asType());

    ExecutableElement method = findMethod("foo", elements.getTypeElement("Foo"));
    assertTrue(method.isVarArgs());
    assertSameType(stringArrayType, findParameter("strings", method).asType());
  }

  @Test
  public void testNotDefault() throws IOException {
    compile(Joiner.on('\n').join("interface Foo {", "  void foo();", "}"));

    assertFalse(findMethod("foo", elements.getTypeElement("Foo")).isDefault());
  }

  @Test
  public void testDefault() throws IOException {
    compile(Joiner.on('\n').join("interface Foo {", "  default void foo() {};", "}"));

    assertTrue(findMethod("foo", elements.getTypeElement("Foo")).isDefault());
  }

  @Test
  public void testGetThrownTypes() throws IOException {
    compile(
        Joiner.on('\n')
            .join(
                "abstract class Foo {",
                "  public abstract void foo() throws Exception, RuntimeException;",
                "}"));

    ExecutableElement method = findMethod("foo", elements.getTypeElement("Foo"));

    assertThat(
        method.getThrownTypes().stream().map(TypeMirror::toString).collect(Collectors.toList()),
        Matchers.contains(
            elements.getTypeElement("java.lang.Exception").asType().toString(),
            elements.getTypeElement("java.lang.RuntimeException").asType().toString()));
  }

  /**
   * See {@link com.facebook.buck.jvm.java.abi.source.TreeBackedAnnotationValueTest} for lots of
   * tests of methods with default values.
   */
  @Test
  public void testGetDefaultValueNoDefault() throws IOException {
    compile(Joiner.on('\n').join("@interface Foo {", "  int value();", "}"));

    assertThat(
        findMethod("value", elements.getTypeElement("Foo")).getDefaultValue(),
        Matchers.nullValue());
  }

  /**
   * See {@link TreeBackedTypeParameterElementTest} for lots of tests of the type parameter elements
   * themselves.
   */
  @Test
  public void testGetTypeParameters() throws IOException {
    compile(Joiner.on('\n').join("class Foo {", "  public <T, U> void foo(T t, U u) { }", "}"));

    assertThat(
        findMethod("foo", elements.getTypeElement("Foo"))
            .getTypeParameters()
            .stream()
            .map(TypeParameterElement::getSimpleName)
            .map(Name::toString)
            .collect(Collectors.toList()),
        Matchers.contains("T", "U"));
  }
}
