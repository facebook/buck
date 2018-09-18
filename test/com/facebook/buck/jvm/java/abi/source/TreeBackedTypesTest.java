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

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiParameterized;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiParameterized.class)
public class TreeBackedTypesTest extends CompilerTreeApiParameterizedTest {
  @Test
  public void testGetDeclaredTypeTopLevelNoGenerics() throws IOException {
    compile("class Foo { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    DeclaredType fooTypeMirror = types.getDeclaredType(fooElement);

    assertEquals(TypeKind.DECLARED, fooTypeMirror.getKind());
    DeclaredType fooDeclaredType = fooTypeMirror;
    assertNotSame(fooElement.asType(), fooDeclaredType);
    assertSame(fooElement, fooDeclaredType.asElement());
    assertEquals(0, fooDeclaredType.getTypeArguments().size());

    TypeMirror enclosingType = fooDeclaredType.getEnclosingType();
    assertEquals(TypeKind.NONE, enclosingType.getKind());
    assertTrue(enclosingType instanceof NoType);
  }

  @Test
  public void testGetDeclaredTypeTopLevelRawType() throws IOException {
    compile("class Foo<T> { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    DeclaredType fooTypeMirror = types.getDeclaredType(fooElement);

    assertEquals(TypeKind.DECLARED, fooTypeMirror.getKind());
    DeclaredType fooDeclaredType = fooTypeMirror;
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
    compile(ImmutableMap.of("Foo.java", "class Foo { }", "Bar.java", "class Bar { }"));

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

  @Test
  public void testIsSameTypeParameterizedType() throws IOException {
    initCompiler();

    TypeElement listElement = elements.getTypeElement("java.util.List");
    TypeMirror stringType = elements.getTypeElement("java.lang.String").asType();
    TypeMirror listStringType1 = types.getDeclaredType(listElement, stringType);
    TypeMirror listStringType2 = types.getDeclaredType(listElement, stringType);

    assertNotSame(listStringType1, listStringType2);
    assertSameType(listStringType1, listStringType2);
  }

  @Test
  public void testIsSameTypeMultiParameterizedType() throws IOException {
    initCompiler();

    TypeElement mapElement = elements.getTypeElement("java.util.Map");
    TypeMirror stringType = elements.getTypeElement("java.lang.String").asType();
    TypeMirror integerType = elements.getTypeElement("java.lang.Integer").asType();
    TypeMirror mapStringIntType1 = types.getDeclaredType(mapElement, stringType, integerType);
    TypeMirror mapStringIntType2 = types.getDeclaredType(mapElement, stringType, integerType);

    assertNotSame(mapStringIntType1, mapStringIntType2);
    assertSameType(mapStringIntType1, mapStringIntType2);
  }

  @Test
  public void testIsNotSameTypeParameterizedType() throws IOException {
    initCompiler();

    TypeElement listElement = elements.getTypeElement("java.util.List");
    TypeMirror stringType = elements.getTypeElement("java.lang.String").asType();
    TypeMirror integerType = elements.getTypeElement("java.lang.Integer").asType();
    TypeMirror listStringType = types.getDeclaredType(listElement, stringType);
    TypeMirror listIntType = types.getDeclaredType(listElement, integerType);

    assertNotSameType(listStringType, listIntType);
  }

  @Test
  public void testIsNotSameTypeMultiParameterizedType() throws IOException {
    initCompiler();

    TypeElement mapElement = elements.getTypeElement("java.util.Map");
    TypeMirror stringType = elements.getTypeElement("java.lang.String").asType();
    TypeMirror integerType = elements.getTypeElement("java.lang.Integer").asType();
    TypeMirror mapStringIntType = types.getDeclaredType(mapElement, stringType, integerType);
    TypeMirror mapStringStringType = types.getDeclaredType(mapElement, stringType, stringType);

    assertNotSameType(mapStringIntType, mapStringStringType);
  }

  @Test
  public void testIsSameTypeArrayType() throws IOException {
    compile("class Foo { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    ArrayType fooArray = types.getArrayType(fooElement.asType());
    ArrayType fooArray2 = types.getArrayType(fooElement.asType());

    assertSameType(fooArray, fooArray2);
    assertNotSameType(fooArray, fooElement.asType());
  }

  @Test
  public void testIsNotSameTypeArrayType() throws IOException {
    compile(Joiner.on('\n').join("class Foo { }", "class Bar { }"));

    TypeMirror fooType = elements.getTypeElement("Foo").asType();
    TypeMirror barType = elements.getTypeElement("Bar").asType();
    ArrayType fooArray = types.getArrayType(fooType);
    ArrayType barArray = types.getArrayType(barType);

    assertNotSameType(fooArray, barArray);
  }

  @Test
  public void testIsSameTypePrimitiveType() throws IOException {
    initCompiler();

    for (TypeKind typeKind : TypeKind.values()) {
      if (typeKind.isPrimitive()) {
        PrimitiveType primitiveType = types.getPrimitiveType(typeKind);
        PrimitiveType primitiveType2 = types.getPrimitiveType(typeKind);

        assertSameType(primitiveType, primitiveType2);
      }
    }
  }

  @Test
  public void testIsNotSameTypePrimitiveType() throws IOException {
    initCompiler();

    PrimitiveType intType = types.getPrimitiveType(TypeKind.INT);
    PrimitiveType longType = types.getPrimitiveType(TypeKind.LONG);

    assertNotSameType(intType, longType);
  }

  @Test
  public void testIsNotSameTypeDifferentTypes() throws IOException {
    initCompiler();

    PrimitiveType intType = types.getPrimitiveType(TypeKind.INT);
    ArrayType intArrayType = types.getArrayType(intType);

    assertNotSameType(intType, intArrayType);
  }

  @Test
  public void testIsSameTypeNullType() throws IOException {
    initCompiler();

    assertSameType(types.getNullType(), types.getNullType());
  }

  @Test
  public void testIsSameTypeUnboundedTypeVariable() throws IOException {
    compile("class Foo<T> extends java.util.ArrayList<T> { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    TypeMirror t1 = fooElement.getTypeParameters().get(0).asType();
    TypeMirror t2 = ((DeclaredType) fooElement.getSuperclass()).getTypeArguments().get(0);

    assertSameType(t1, t2);
  }

  @Test
  public void testIsNotSameTypeUnboundedTypeVariable() throws IOException {
    compile(Joiner.on('\n').join("class Foo<T> { }", "class Bar<T> { }"));

    TypeMirror fooT = elements.getTypeElement("Foo").getTypeParameters().get(0).asType();
    TypeMirror barT = elements.getTypeElement("Bar").getTypeParameters().get(0).asType();

    assertNotSameType(fooT, barT);
  }

  @Test
  public void testIsSameTypeBoundedTypeVariable() throws IOException {
    compile(
        Joiner.on('\n')
            .join(
                "class Foo<T extends java.lang.CharSequence & java.lang.Runnable>",
                "    extends java.util.ArrayList<T>{ }"));

    TypeElement fooElement = elements.getTypeElement("Foo");
    TypeMirror t1 = fooElement.getTypeParameters().get(0).asType();
    TypeMirror t2 = ((DeclaredType) fooElement.getSuperclass()).getTypeArguments().get(0);

    assertSameType(t1, t2);
  }

  @Test
  public void testIsSameTypeIntersectionType() throws IOException {
    compile(
        Joiner.on('\n')
            .join(
                "class Foo<T extends java.lang.CharSequence & java.lang.Runnable> { }",
                "class Bar<T extends java.lang.CharSequence & java.lang.Runnable> { }"));

    IntersectionType fooType = (IntersectionType) getTypeParameterUpperBound("Foo", 0);
    IntersectionType barType = (IntersectionType) getTypeParameterUpperBound("Bar", 0);

    assertSameType(fooType, barType);
  }

  @Test
  public void testIsNotSameTypeIntersectionTypeDifferentSize() throws IOException {
    compile(
        Joiner.on('\n')
            .join(
                "class Foo<T extends java.lang.CharSequence & java.lang.Runnable> { }",
                "class Bar<T extends java.lang.CharSequence & java.lang.Runnable & java.io.Closeable> { }"));

    IntersectionType fooType = (IntersectionType) getTypeParameterUpperBound("Foo", 0);
    IntersectionType barType = (IntersectionType) getTypeParameterUpperBound("Bar", 0);

    assertNotSameType(fooType, barType);
  }

  @Test
  public void testIsNotSameTypeIntersectionTypeDifferentSizeReversed() throws IOException {
    compile(
        Joiner.on('\n')
            .join(
                "class Foo<T extends java.lang.CharSequence & java.lang.Runnable & java.io.Closeable> { }",
                "class Bar<T extends java.lang.CharSequence & java.lang.Runnable> { }"));

    IntersectionType fooType = (IntersectionType) getTypeParameterUpperBound("Foo", 0);
    IntersectionType barType = (IntersectionType) getTypeParameterUpperBound("Bar", 0);

    assertNotSameType(fooType, barType);
  }

  /**
   * We're not exactly sure why intersection types with the same bounds but in a different order are
   * considered the same type; after all, they can have different erasures. However, the javac
   * implementation behaves that way, so we must as well.
   *
   * <p>The relevant JLS8 sections are 4.4 and 4.9, if any future person wants to go see if they can
   * grok why this behavior is correct.
   */
  @Test
  public void testIsSameTypeIntersectionTypeDifferentOrder() throws IOException {
    compile(
        Joiner.on('\n')
            .join(
                "class Foo<T extends java.lang.CharSequence & java.lang.Runnable> { }",
                "class Bar<T extends java.lang.Runnable & java.lang.CharSequence> { }"));

    IntersectionType fooType = (IntersectionType) getTypeParameterUpperBound("Foo", 0);
    IntersectionType barType = (IntersectionType) getTypeParameterUpperBound("Bar", 0);

    assertSameType(fooType, barType);
  }

  @Test
  public void testIsNotSameTypeIntersectionTypeDifferentContents() throws IOException {
    compile(
        Joiner.on('\n')
            .join(
                "class Foo<T extends java.lang.CharSequence & java.lang.Runnable> { }",
                "class Bar<T extends java.lang.CharSequence & java.io.Closeable> { }"));

    IntersectionType fooType = (IntersectionType) getTypeParameterUpperBound("Foo", 0);
    IntersectionType barType = (IntersectionType) getTypeParameterUpperBound("Bar", 0);

    assertNotSameType(fooType, barType);
  }

  @Test
  public void testIsSameTypeWildcardType() throws IOException {
    compile("class Foo { }");

    WildcardType wildcardType = types.getWildcardType(null, null);
    WildcardType wildcardType2 = types.getWildcardType(null, null);

    // According to the docs, wildcard types are never equal to one another, even if they're
    // the exact same instance.
    assertNotSameType(wildcardType, wildcardType);
    assertNotSameType(wildcardType, wildcardType2);
  }

  @Test
  public void testErasureOfPrimitiveIsItself() throws IOException {
    initCompiler();

    TypeMirror intType = types.getPrimitiveType(TypeKind.INT);

    assertSameType(intType, types.erasure(intType));
  }

  @Test
  public void testErasureOfNonGenericIsItself() throws IOException {
    compile("class Foo { }");

    TypeMirror fooType = elements.getTypeElement("Foo").asType();

    assertSameType(fooType, types.erasure(fooType));
  }

  @Test
  public void testErasureOfArrayIsArrayOfErasedComponent() throws IOException {
    compile("class Foo<T> { }");

    TypeElement fooElement = elements.getTypeElement("Foo");
    TypeMirror stringType = elements.getTypeElement("java.lang.String").asType();
    TypeMirror fooOfString = types.getDeclaredType(fooElement, stringType);
    TypeMirror fooErasure = types.erasure(fooOfString);
    TypeMirror multiArrayOfFooOfString = types.getArrayType(types.getArrayType(fooOfString));
    TypeMirror multiArrayOfFoo = types.getArrayType(types.getArrayType(fooErasure));

    assertSameType(multiArrayOfFoo, types.erasure(multiArrayOfFooOfString));
  }

  @Test
  public void testErasureOfInnerType() throws IOException {
    compile(
        Joiner.on('\n')
            .join(
                "class Foo<T> {",
                "  static Foo<String>.Inner<Integer> field;",
                "  class Inner<U> {",
                "  }",
                "}"));

    TypeElement fooElement = elements.getTypeElement("Foo");
    TypeElement innerElement = elements.getTypeElement("Foo.Inner");
    TypeMirror genericInnerType =
        ElementFilter.fieldsIn(fooElement.getEnclosedElements()).get(0).asType();

    DeclaredType fooType = types.getDeclaredType(fooElement);
    TypeMirror erasedInnerType = types.getDeclaredType(fooType, innerElement);

    assertSameType(erasedInnerType, types.erasure(genericInnerType));
  }

  @Test
  public void testErasureOfSetOfStringIsSet() throws IOException {
    initCompiler();

    TypeElement setElement = elements.getTypeElement("java.util.Set");
    TypeMirror stringType = elements.getTypeElement("java.lang.String").asType();

    TypeMirror setType = types.getDeclaredType(setElement);
    TypeMirror setOfStringType = types.getDeclaredType(setElement, stringType);

    assertSameType(setType, types.erasure(setOfStringType));
  }

  @Test
  public void testErasureOfSetOfUserDefinedTypeIsSet() throws IOException {
    compile("class Foo { }");

    TypeElement setElement = elements.getTypeElement("java.util.Set");
    TypeMirror fooType = elements.getTypeElement("Foo").asType();

    TypeMirror setType = types.getDeclaredType(setElement);
    TypeMirror setOfFooType = types.getDeclaredType(setElement, fooType);

    assertSameType(setType, types.erasure(setOfFooType));
  }
}
