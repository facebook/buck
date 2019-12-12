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

import static org.junit.Assert.assertNotSame;

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiParameterized;
import com.google.common.base.Joiner;
import java.io.IOException;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiParameterized.class)
public class TypeResolverTest extends CompilerTreeApiParameterizedTest {
  @Test
  public void testParameterizedTypeResolves() throws IOException {
    compile(
        Joiner.on('\n')
            .join(
                "abstract class Foo extends java.util.ArrayList<java.lang.String> { }",
                "abstract class Bar extends java.util.ArrayList<java.lang.String> { }"));

    TypeElement listElement = elements.getTypeElement("java.util.ArrayList");
    TypeElement stringElement = elements.getTypeElement("java.lang.String");
    DeclaredType expectedSuperclass = types.getDeclaredType(listElement, stringElement.asType());

    TypeElement fooElement = elements.getTypeElement("Foo");
    TypeElement barElement = elements.getTypeElement("Bar");
    DeclaredType fooSuperclass = (DeclaredType) fooElement.getSuperclass();
    DeclaredType barSuperclass = (DeclaredType) barElement.getSuperclass();

    assertNotSame(expectedSuperclass, fooSuperclass);
    assertSameType(expectedSuperclass, fooSuperclass);
    assertSameType(fooSuperclass, barSuperclass);
  }

  @Test
  public void testDeeplyParameterizedTypeResolves() throws IOException {
    compile(
        Joiner.on('\n')
            .join(
                "abstract class Foo",
                "    extends java.util.HashMap<",
                "        java.util.ArrayList<java.lang.String>, ",
                "        java.util.HashSet<java.lang.Integer>> { }"));

    TypeElement mapElement = elements.getTypeElement("java.util.HashMap");
    TypeElement listElement = elements.getTypeElement("java.util.ArrayList");
    TypeElement setElement = elements.getTypeElement("java.util.HashSet");
    TypeMirror stringType = elements.getTypeElement("java.lang.String").asType();
    TypeMirror integerType = elements.getTypeElement("java.lang.Integer").asType();
    TypeMirror listStringType = types.getDeclaredType(listElement, stringType);
    TypeMirror setIntType = types.getDeclaredType(setElement, integerType);
    TypeMirror crazyMapType = types.getDeclaredType(mapElement, listStringType, setIntType);

    TypeElement fooElement = elements.getTypeElement("Foo");

    assertSameType(crazyMapType, fooElement.getSuperclass());
  }

  @Test
  public void testArrayTypeResolves() throws IOException {
    compile("abstract class Foo extends java.util.ArrayList<java.lang.String[]> { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    TypeElement listElement = elements.getTypeElement("java.util.ArrayList");
    TypeMirror stringType = elements.getTypeElement("java.lang.String").asType();
    ArrayType stringArrayType = types.getArrayType(stringType);

    DeclaredType expectedSuperclass = types.getDeclaredType(listElement, stringArrayType);

    assertSameType(expectedSuperclass, fooElement.getSuperclass());
  }

  @Test
  public void testMultiDimArrayTypeResolves() throws IOException {
    compile("abstract class Foo extends java.util.ArrayList<java.lang.String[][]> { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    TypeElement listElement = elements.getTypeElement("java.util.ArrayList");
    TypeMirror stringType = elements.getTypeElement("java.lang.String").asType();
    ArrayType stringArrayType = types.getArrayType(stringType);
    ArrayType stringArrayArrayType = types.getArrayType(stringArrayType);

    DeclaredType expectedSuperclass = types.getDeclaredType(listElement, stringArrayArrayType);

    assertSameType(expectedSuperclass, fooElement.getSuperclass());
  }

  @Test
  public void testPrimitiveArrayTypeResolves() throws IOException {
    compile("abstract class Foo extends java.util.ArrayList<int[]> { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    TypeElement listElement = elements.getTypeElement("java.util.ArrayList");
    TypeMirror intType = types.getPrimitiveType(TypeKind.INT);
    ArrayType intArrayType = types.getArrayType(intType);

    DeclaredType expectedSuperclass = types.getDeclaredType(listElement, intArrayType);

    assertSameType(expectedSuperclass, fooElement.getSuperclass());
  }

  @Test
  public void testTypeVariableResolves() throws IOException {
    compile("abstract class Foo<T extends java.lang.Runnable> extends java.util.ArrayList<T> { }");

    TypeElement fooElement = elements.getTypeElement("Foo");

    TypeElement listElement = elements.getTypeElement("java.util.ArrayList");
    TypeMirror tType = fooElement.getTypeParameters().get(0).asType();

    DeclaredType expectedSuperclass = types.getDeclaredType(listElement, tType);
    assertSameType(expectedSuperclass, fooElement.getSuperclass());
  }
}
