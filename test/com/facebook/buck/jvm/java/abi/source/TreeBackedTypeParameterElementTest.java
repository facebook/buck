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

import static org.junit.Assert.assertSame;

import com.facebook.buck.testutil.CompilerTreeApiParameterized;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.List;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeMirror;

@RunWith(CompilerTreeApiParameterized.class)
public class TreeBackedTypeParameterElementTest extends CompilerTreeApiParameterizedTest {
  @Test
  public void testGetSimpleName() throws IOException {
    compile("class Foo<T> { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    final List<? extends TypeParameterElement> typeParameters = fooElement.getTypeParameters();

    assertSame(1, typeParameters.size());
    TypeParameterElement typeParam = typeParameters.get(0);

    assertNameEquals("T", typeParam.getSimpleName());
  }

  @Test
  public void testGetGenericElement() throws IOException {
    compile("class Foo<T> { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    final List<? extends TypeParameterElement> typeParameters = fooElement.getTypeParameters();

    assertSame(1, typeParameters.size());
    TypeParameterElement typeParam = typeParameters.get(0);

    assertSame(fooElement, typeParam.getGenericElement());
  }

  @Test
  public void testGetEnclosingElement() throws IOException {
    compile("class Foo<T> { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    final List<? extends TypeParameterElement> typeParameters = fooElement.getTypeParameters();

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
}
