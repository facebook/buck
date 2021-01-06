/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java.abi.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiParameterized;
import java.io.IOException;
import java.util.List;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiParameterized.class)
public class StandaloneTypeVariableTest extends CompilerTreeApiParameterizedTest {
  @Test
  public void testGetKind() throws IOException {
    compile("class Foo<T> { }");

    TypeVariable tVar =
        (TypeVariable) elements.getTypeElement("Foo").getTypeParameters().get(0).asType();

    assertSame(TypeKind.TYPEVAR, tVar.getKind());
  }

  @Test
  public void testAsElement() throws IOException {
    compile("class Foo<T> { }");

    TypeParameterElement tElement = elements.getTypeElement("Foo").getTypeParameters().get(0);
    TypeVariable tVar = (TypeVariable) tElement.asType();

    assertSame(tElement, tVar.asElement());
  }

  @Test
  public void testGetUpperBoundUnbounded() throws IOException {
    compile("class Foo<T> { }");

    TypeMirror objectType = elements.getTypeElement("java.lang.Object").asType();

    TypeVariable tVar =
        (TypeVariable) elements.getTypeElement("Foo").getTypeParameters().get(0).asType();

    assertSameType(objectType, tVar.getUpperBound());
  }

  @Test
  public void testGetLowerBoundUnbounded() throws IOException {
    compile("class Foo<T> { }");

    TypeVariable tVar =
        (TypeVariable) elements.getTypeElement("Foo").getTypeParameters().get(0).asType();

    assertSameType(types.getNullType(), tVar.getLowerBound());
  }

  @Test
  public void testToStringUnbounded() throws IOException {
    compile("class Foo<T> { }");

    TypeVariable typeVariable =
        (TypeVariable) elements.getTypeElement("Foo").getTypeParameters().get(0).asType();

    assertEquals("T", typeVariable.toString());
  }

  @Test
  public void testGetUpperBoundMultipleBounds() throws IOException {
    compile("class Foo<T extends java.lang.CharSequence & java.lang.Runnable> { }");

    TypeMirror charSequenceType = elements.getTypeElement("java.lang.CharSequence").asType();
    TypeMirror runnableType = elements.getTypeElement("java.lang.Runnable").asType();

    TypeVariable tVar =
        (TypeVariable) elements.getTypeElement("Foo").getTypeParameters().get(0).asType();

    IntersectionType upperBound = (IntersectionType) tVar.getUpperBound();

    List<? extends TypeMirror> bounds = upperBound.getBounds();
    assertSame(2, bounds.size());
    assertSameType(charSequenceType, bounds.get(0));
    assertSameType(runnableType, bounds.get(1));
  }

  @Test
  public void testGetLowerBoundMultipleBounds() throws IOException {
    compile("class Foo<T extends java.lang.Runnable & java.lang.CharSequence> { }");

    TypeVariable tVar =
        (TypeVariable) elements.getTypeElement("Foo").getTypeParameters().get(0).asType();

    assertSameType(types.getNullType(), tVar.getLowerBound());
  }

  @Test
  public void testToStringBounded() throws IOException {
    compile("class Foo<T extends java.lang.CharSequence> { }");

    TypeVariable typeVariable =
        (TypeVariable) elements.getTypeElement("Foo").getTypeParameters().get(0).asType();

    assertEquals("T", typeVariable.toString());
  }

  @Test
  public void testToStringMultipleBounds() throws IOException {
    compile("class Foo<T extends java.lang.CharSequence & java.lang.Runnable> { }");

    TypeVariable typeVariable =
        (TypeVariable) elements.getTypeElement("Foo").getTypeParameters().get(0).asType();

    assertEquals("T", typeVariable.toString());
  }
}
