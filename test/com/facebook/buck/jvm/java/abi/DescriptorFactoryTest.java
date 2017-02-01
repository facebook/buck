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

package com.facebook.buck.jvm.java.abi;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.jvm.java.testutil.CompilerTreeApiTest;
import com.facebook.buck.jvm.java.testutil.CompilerTreeApiTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

import javax.lang.model.type.TypeKind;

@RunWith(CompilerTreeApiTestRunner.class)
public class DescriptorFactoryTest extends CompilerTreeApiTest {

  private DescriptorFactory descriptorFactory;

  @Before
  public void setUp() throws IOException {
    initCompiler();
    descriptorFactory = new DescriptorFactory(elements);
  }

  @Test
  public void testVoidDescriptor() {
    assertEquals("V", descriptorFactory.getDescriptor(types.getNoType(TypeKind.VOID)));
  }

  @Test
  public void testBooleanDescriptor() {
    assertEquals("Z", descriptorFactory.getDescriptor(types.getPrimitiveType(TypeKind.BOOLEAN)));
  }

  @Test
  public void testByteDescriptor() {
    assertEquals("B", descriptorFactory.getDescriptor(types.getPrimitiveType(TypeKind.BYTE)));
  }

  @Test
  public void testCharDescriptor() {
    assertEquals("C", descriptorFactory.getDescriptor(types.getPrimitiveType(TypeKind.CHAR)));
  }

  @Test
  public void testShortDescriptor() {
    assertEquals("S", descriptorFactory.getDescriptor(types.getPrimitiveType(TypeKind.SHORT)));
  }

  @Test
  public void testIntDescriptor() {
    assertEquals("I", descriptorFactory.getDescriptor(types.getPrimitiveType(TypeKind.INT)));
  }

  @Test
  public void testLongDescriptor() {
    assertEquals("J", descriptorFactory.getDescriptor(types.getPrimitiveType(TypeKind.LONG)));
  }

  @Test
  public void testFloatDescriptor() {
    assertEquals("F", descriptorFactory.getDescriptor(types.getPrimitiveType(TypeKind.FLOAT)));
  }

  @Test
  public void testDoubleDescriptor() {
    assertEquals("D", descriptorFactory.getDescriptor(types.getPrimitiveType(TypeKind.DOUBLE)));
  }

  @Test
  public void testPrimitiveArrayDescriptor() {
    assertEquals(
        "[D",
        descriptorFactory.getDescriptor(
            types.getArrayType(
                types.getPrimitiveType(TypeKind.DOUBLE))));
  }

  @Test
  public void testTopLevelClassDescriptor() {
    assertEquals(
        "Ljava/lang/String;",
        descriptorFactory.getDescriptor(elements.getTypeElement("java.lang.String")));
  }

  @Test
  public void testNestedClassDescriptor() {
    assertEquals(
        "Ljava/util/Map$Entry;",
        descriptorFactory.getDescriptor(elements.getTypeElement("java.util.Map.Entry")));
  }

  @Test
  public void testObjectArrayDescriptor() {
    assertEquals(
        "[Ljava/lang/Object;",
        descriptorFactory.getDescriptor(
            types.getArrayType(elements.getTypeElement("java.lang.Object").asType())));
  }

  @Test
  public void testMultiDimObjectArrayDescriptor() {
    assertEquals(
        "[[Ljava/lang/Object;",
        descriptorFactory.getDescriptor(
            types.getArrayType(
                types.getArrayType(
                    elements.getTypeElement("java.lang.Object").asType()))));
  }

  @Test
  public void testParameterlessMethodDescriptor() {
    assertEquals(
        "()Ljava/lang/String;",
        descriptorFactory.getDescriptor(findMethod(
            "toString",
            elements.getTypeElement("java.lang.Object"))));
  }

  @Test
  public void testMethodWithParametersDescriptor() {
    assertEquals(
        "(II)I",
        descriptorFactory.getDescriptor(findMethod(
            "codePointCount",
            elements.getTypeElement("java.lang.String"))));
  }

  @Test
  public void testMethodWithGenericParametersDescriptor() {
    assertEquals(
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
        descriptorFactory.getDescriptor(findMethod(
            "put",
            elements.getTypeElement("java.util.Map"))));
  }

  @Test
  public void testTypeVarWithMultipleBoundsDescriptor() throws IOException {
    compile("class Foo <T extends Runnable & CharSequence> { }");

    assertEquals(
        "Ljava/lang/Runnable;",
        descriptorFactory.getDescriptor(elements.getTypeElement("Foo").getTypeParameters().get(0)));
  }

  @Test
  public void testTypeVarInternalName() throws IOException {
    compile("class Foo<T extends java.util.List<U>, U> { }");

    assertEquals(
        "java/util/List",
        descriptorFactory.getInternalName(
            elements.getTypeElement("Foo").getTypeParameters().get(0).asType()));
  }

  @Test
  public void testTypeVarWithMultipleBoundsInDifferentOrderDescriptor() throws IOException {
    compile("class Foo <T extends CharSequence & Runnable> { }");

    assertEquals(
        "Ljava/lang/CharSequence;",
        descriptorFactory.getDescriptor(elements.getTypeElement("Foo").getTypeParameters().get(0)));
  }
}
