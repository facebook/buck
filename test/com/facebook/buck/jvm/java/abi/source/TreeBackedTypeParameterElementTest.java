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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiParameterized;
import java.io.IOException;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleElementVisitor8;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiParameterized.class)
public class TreeBackedTypeParameterElementTest extends CompilerTreeApiParameterizedTest {
  @Test
  public void testGetSimpleName() throws IOException {
    compile("class Foo<T> { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    List<? extends TypeParameterElement> typeParameters = fooElement.getTypeParameters();

    assertSame(1, typeParameters.size());
    TypeParameterElement typeParam = typeParameters.get(0);

    assertNameEquals("T", typeParam.getSimpleName());
  }

  @Test
  public void testGetSimpleNameWithBoundedParameter() throws IOException {
    compile("class Foo<T extends java.lang.Runnable> { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    List<? extends TypeParameterElement> typeParameters = fooElement.getTypeParameters();

    assertSame(1, typeParameters.size());
    TypeParameterElement typeParam = typeParameters.get(0);

    assertNameEquals("T", typeParam.getSimpleName());
  }

  @Test
  public void testGetKind() throws IOException {
    compile("class Foo<T> { }");

    assertSame(
        ElementKind.TYPE_PARAMETER,
        elements.getTypeElement("Foo").getTypeParameters().get(0).getKind());
  }

  @Test
  public void testAccept() throws IOException {
    compile("class Foo<T> { }");

    TypeParameterElement expectedTypeParameter =
        elements.getTypeElement("Foo").getTypeParameters().get(0);
    Object expectedResult = new Object();
    Object actualResult =
        expectedTypeParameter.accept(
            new SimpleElementVisitor8<Object, Object>() {
              @Override
              protected Object defaultAction(Element e, Object o) {
                return null;
              }

              @Override
              public Object visitTypeParameter(TypeParameterElement actualTypeParameter, Object o) {
                assertSame(expectedTypeParameter, actualTypeParameter);
                return o;
              }
            },
            expectedResult);

    assertSame(expectedResult, actualResult);
  }

  @Test
  public void testToString() throws IOException {
    compile("class Foo<T extends java.lang.Runnable> { }");

    TypeParameterElement typeParam = elements.getTypeElement("Foo").getTypeParameters().get(0);

    assertEquals("T", typeParam.toString());
  }

  @Test
  public void testGetGenericElement() throws IOException {
    compile("class Foo<T> { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    List<? extends TypeParameterElement> typeParameters = fooElement.getTypeParameters();

    assertSame(1, typeParameters.size());
    TypeParameterElement typeParam = typeParameters.get(0);

    assertSame(fooElement, typeParam.getGenericElement());
  }

  @Test
  public void testGetEnclosingElement() throws IOException {
    compile("class Foo<T> { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    List<? extends TypeParameterElement> typeParameters = fooElement.getTypeParameters();

    assertSame(1, typeParameters.size());
    TypeParameterElement typeParam = typeParameters.get(0);

    assertSame(fooElement, typeParam.getGenericElement());
  }

  @Test
  public void testUnboundedTypeParameter() throws IOException {
    compile("class Foo<T> { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    TypeMirror objectType = elements.getTypeElement("java.lang.Object").asType();
    List<? extends TypeParameterElement> typeParameters = fooElement.getTypeParameters();

    assertSame(1, typeParameters.size());
    TypeParameterElement typeParam = typeParameters.get(0);
    List<? extends TypeMirror> bounds = typeParam.getBounds();

    assertSame(1, bounds.size());
    assertSameType(objectType, bounds.get(0));
  }

  @Test
  public void testMultipleTypeParameters() throws IOException {
    compile("class Foo<T, U extends java.lang.CharSequence> { }");

    TypeMirror objectType = elements.getTypeElement("java.lang.Object").asType();
    TypeMirror charSequenceType = elements.getTypeElement("java.lang.CharSequence").asType();

    TypeElement fooElement = elements.getTypeElement("Foo");
    List<? extends TypeParameterElement> typeParameters = fooElement.getTypeParameters();
    assertSame(2, typeParameters.size());

    TypeParameterElement tParam = typeParameters.get(0);
    List<? extends TypeMirror> bounds = tParam.getBounds();
    assertSame(1, bounds.size());
    assertSameType(objectType, bounds.get(0));

    TypeParameterElement uParam = typeParameters.get(1);
    bounds = uParam.getBounds();
    assertSame(1, bounds.size());
    assertSameType(charSequenceType, bounds.get(0));
  }

  @Test
  public void testSuperclassBoundedTypeParameter() throws IOException {
    compile("class Foo<T extends java.lang.CharSequence> { }");

    TypeMirror charSequenceType = elements.getTypeElement("java.lang.CharSequence").asType();

    TypeElement fooElement = elements.getTypeElement("Foo");
    List<? extends TypeParameterElement> typeParameters = fooElement.getTypeParameters();
    assertSame(1, typeParameters.size());
    TypeParameterElement typeParam = typeParameters.get(0);
    List<? extends TypeMirror> bounds = typeParam.getBounds();

    assertSame(1, bounds.size());
    assertSameType(charSequenceType, bounds.get(0));
  }

  @Test
  public void testTypeParameterBoundedTypeParameter() throws IOException {
    compile("class Foo<T, U extends T> { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    TypeParameterElement uElement = fooElement.getTypeParameters().get(1);
    TypeMirror tType = fooElement.getTypeParameters().get(0).asType();

    assertSameType(tType, uElement.getBounds().get(0));
  }

  @Test
  public void testForwardReferenceTypeParameterBoundedTypeParameter() throws IOException {
    compile("class Foo<T extends U, U> { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    TypeParameterElement tElement = fooElement.getTypeParameters().get(0);
    TypeMirror uType = fooElement.getTypeParameters().get(1).asType();

    assertSameType(uType, tElement.getBounds().get(0));
  }

  @Test
  public void testMultipleBoundedTypeParameter() throws IOException {
    compile(
        "class Foo<T extends java.lang.CharSequence & java.lang.Runnable & java.io.Closeable> { }");

    TypeMirror charSequenceType = elements.getTypeElement("java.lang.CharSequence").asType();
    TypeMirror runnableType = elements.getTypeElement("java.lang.Runnable").asType();
    TypeMirror closeableType = elements.getTypeElement("java.io.Closeable").asType();

    TypeElement fooElement = elements.getTypeElement("Foo");
    List<? extends TypeParameterElement> typeParameters = fooElement.getTypeParameters();
    assertSame(1, typeParameters.size());
    TypeParameterElement typeParam = typeParameters.get(0);
    List<? extends TypeMirror> bounds = typeParam.getBounds();

    assertSame(3, bounds.size());
    assertSameType(charSequenceType, bounds.get(0));
    assertSameType(runnableType, bounds.get(1));
    assertSameType(closeableType, bounds.get(2));
  }

  @Test
  public void testEnclosingElementDoesNotEnclose() throws IOException {
    compile("class Foo<T> { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    TypeParameterElement tElement = fooElement.getTypeParameters().get(0);

    assertSame(fooElement, tElement.getEnclosingElement());
    assertFalse(fooElement.getEnclosedElements().contains(tElement));
  }
}
