/*
 * Copyright 2016-present Facebook, Inc.
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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.testutil.CompilerTreeApiParameterized;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

@RunWith(CompilerTreeApiParameterized.class)
public class TreeBackedTypesTest extends CompilerTreeApiParameterizedTest {
  @Test
  public void testGetDeclaredTypeTopLevelNoGenerics() throws IOException {
    compile("class Foo { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    TypeMirror fooTypeMirror = types.getDeclaredType(fooElement);

    assertEquals(TypeKind.DECLARED, fooTypeMirror.getKind());
    DeclaredType fooDeclaredType = (DeclaredType) fooTypeMirror;
    assertNotSame(fooElement.asType(), fooDeclaredType);
    assertSame(fooElement, fooDeclaredType.asElement());
    assertEquals(0, fooDeclaredType.getTypeArguments().size());

    TypeMirror enclosingType = fooDeclaredType.getEnclosingType();
    assertEquals(TypeKind.NONE, enclosingType.getKind());
    assertTrue(enclosingType instanceof NoType);
  }

  @Test
  public void testIsSameTypeTopLevelNoGenerics() throws IOException {
    compile("class Foo { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    TypeMirror fooTypeMirror = types.getDeclaredType(fooElement);
    TypeMirror fooTypeMirror2 = types.getDeclaredType(fooElement);

    assertSameType(fooTypeMirror, fooTypeMirror2);
  }

  @Test
  public void testIsNotSameTypeStaticNoGenerics() throws IOException {
    compile(ImmutableMap.of(
        "Foo.java",
        "class Foo { }",
        "Bar.java",
        "class Bar { }"));

    TypeMirror fooType = elements.getTypeElement("Foo").asType();
    TypeMirror barType = elements.getTypeElement("Bar").asType();

    assertNotSameType(fooType, barType);
  }

  @Test
  public void testIsSameTypeJavacTypeTopLevelNoGenerics() throws IOException {
    initCompiler();

    TypeElement objectElement = elements.getTypeElement("java.lang.Object");
    TypeMirror objectTypeMirror = types.getDeclaredType(objectElement);
    TypeMirror objectTypeMirror2 = types.getDeclaredType(objectElement);

    assertSameType(objectTypeMirror, objectTypeMirror2);
  }

  @Test
  public void testIsNotSameTypeJavacTypeTopLevelNoGenerics() throws IOException {
    initCompiler();

    TypeMirror objectType = elements.getTypeElement("java.lang.Object").asType();
    TypeMirror stringType = elements.getTypeElement("java.lang.String").asType();

    assertNotSameType(objectType, stringType);
  }

  private void assertSameType(TypeMirror expected, TypeMirror actual) {
    if (!types.isSameType(expected, actual)) {
      fail(String.format("Types are not the same.\nExpected: %s\nActual: %s", expected, actual));
    }
  }

  private void assertNotSameType(TypeMirror expected, TypeMirror actual) {
    if (types.isSameType(expected, actual)) {
      fail(String.format("Expected different types, but both were: %s", expected));
    }
  }
}
