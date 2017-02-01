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
import static org.junit.Assert.assertThat;

import com.facebook.buck.jvm.java.testutil.CompilerTreeApiTest;
import com.facebook.buck.jvm.java.testutil.CompilerTreeApiTestRunner;
import com.google.common.base.Joiner;

import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.objectweb.asm.Opcodes;

import java.io.IOException;

@RunWith(CompilerTreeApiTestRunner.class)
public class AccessFlagsTest extends CompilerTreeApiTest {

  @Test
  public void testPublicFlag() throws IOException {
    testFieldFlags("public", Opcodes.ACC_PUBLIC);
    testMethodFlags("public", Opcodes.ACC_PUBLIC);
    testClassFlags("public", Opcodes.ACC_PUBLIC);
  }

  @Test
  public void testProtectedFlag() throws IOException {
    testFieldFlags("protected", Opcodes.ACC_PROTECTED);
    testMethodFlags("protected", Opcodes.ACC_PROTECTED);
  }

  @Test
  public void testPrivateFlag() throws IOException {
    testFieldFlags("private", Opcodes.ACC_PRIVATE);
    testMethodFlags("private", Opcodes.ACC_PRIVATE);
  }

  @Test
  public void testNoFlagForDefaultVisibility() throws IOException {
    testFieldFlags("", 0);
    testMethodFlags("", 0);
    testClassFlags("", 0);
  }

  @Test
  public void testNoFlagForInterfaceDefaultMethod() throws IOException {
    compile(Joiner.on('\n').join(
       "interface Foo {",
        "  default void foo() { }",
        "}"));

    assertEquals(Opcodes.ACC_PUBLIC,
        AccessFlags.getAccessFlags(findMethod("foo", elements.getTypeElement("Foo"))));
  }

  @Test
  public void testStaticFlag() throws IOException {
    testFieldFlags("static", Opcodes.ACC_STATIC);
    testMethodFlags("static", Opcodes.ACC_STATIC);
    testTypeFlags(
        Joiner.on('\n').join(
            "class Foo {",
            "  static class Inner { }",
            "}"),
        "Foo.Inner",
        Opcodes.ACC_STATIC | Opcodes.ACC_SUPER);
  }

  @Test
  public void testFinalFlag() throws IOException {
    testFieldFlags("final", Opcodes.ACC_FINAL);
    testMethodFlags("final", Opcodes.ACC_FINAL);
    testClassFlags("final", Opcodes.ACC_FINAL);
  }

  @Test
  public void testVolatileFlag() throws IOException {
    testFieldFlags("volatile", Opcodes.ACC_VOLATILE);
  }

  @Test
  public void testTransientFlag() throws IOException {
    testFieldFlags("transient", Opcodes.ACC_TRANSIENT);
  }

  @Test
  public void testAbstractFlag() throws IOException {
    testClassFlags("abstract", Opcodes.ACC_ABSTRACT);

    compile(Joiner.on('\n').join(
        "abstract class Foo {",
        "  abstract void foo();",
        "}"));
    assertEquals(
        Opcodes.ACC_ABSTRACT,
        AccessFlags.getAccessFlags(findMethod("foo", elements.getTypeElement("Foo"))));
  }

  @Test
  public void testSynchronizedFlag() throws IOException {
    testMethodFlags("synchronized", Opcodes.ACC_SYNCHRONIZED);
  }

  @Test
  public void testFpStrictFlag() throws IOException {
    testMethodFlags("strictfp", Opcodes.ACC_STRICT);
  }

  @Test
  public void testNativeFlag() throws IOException {
    compile(Joiner.on('\n').join(
        "class Foo {",
        "  native void method();",
        "}"));

    assertEquals(
        Opcodes.ACC_NATIVE,
        AccessFlags.getAccessFlags(
            findMethod("method", elements.getTypeElement("Foo"))));
  }

  @Test
  public void testMultipleFlags() throws IOException {
    testMethodFlags("public static", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
    testFieldFlags("public static", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
  }

  @Test
  public void testVarArgsFlag() throws IOException {
    compile(Joiner.on('\n').join(
        "class Foo {",
        "  void method(String... s) { }",
        "}"));

    assertEquals(
        Opcodes.ACC_VARARGS,
        AccessFlags.getAccessFlags(
            findMethod("method", elements.getTypeElement("Foo"))));
  }

  @Test
  public void testDeprecatedPseudoFlag() throws IOException {
    testFieldFlags("@Deprecated", Opcodes.ACC_DEPRECATED);
    testMethodFlags("@Deprecated", Opcodes.ACC_DEPRECATED);
  }

  @Test
  public void testAnnotationTypeFlags() throws IOException {
    testTypeFlags(

        "@java.lang.annotation.Documented @interface Foo { }",
        "Foo",
        Opcodes.ACC_ANNOTATION | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT);
  }

  @Test
  public void testInterfaceTypeFlags() throws IOException {
    testTypeFlags(
        "interface Foo { }",
        "Foo",
        Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT);
  }

  @Test
  public void testEnumTypeFlags() throws IOException {
    testTypeFlags(
        "enum Foo { Item }",
        "Foo",
        Opcodes.ACC_ENUM | Opcodes.ACC_SUPER | Opcodes.ACC_FINAL);
  }

  @Test
  public void testEnumAbstractFlagIsInferred() throws IOException {
    testTypeFlags(
        Joiner.on('\n').join(
            "enum Foo {",
            "  Value {",
            "    int get() { return 3; }",
            "  };",
            "  abstract int get();",
            "}"),
        "Foo",
        Opcodes.ACC_ENUM | Opcodes.ACC_SUPER | Opcodes.ACC_ABSTRACT);
  }

  @Test
  public void testEnumVarFlags() throws IOException {
    compile("enum Foo { Item }");
    assertEquals(
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
        AccessFlags.getAccessFlags(
            findField("Item", elements.getTypeElement("Foo"))));
  }

  private void testClassFlags(String modifiers, int expectedFlags) throws IOException {
    testTypeFlags(
        String.format("%s class Foo { }", modifiers),
        "Foo",
        expectedFlags | Opcodes.ACC_SUPER);
  }

  private void testTypeFlags(
      String content,
      String typeName,
      int expectedFlags) throws IOException {
    compile(content);
    assertThat(diagnostics.getDiagnostics(), Matchers.empty());
    assertEquals(
        expectedFlags,
        AccessFlags.getAccessFlags(elements.getTypeElement(typeName)));
  }

  private void testMethodFlags(String modifiers, int expectedFlags) throws IOException {
    compile(Joiner.on('\n').join(
        "class Foo {",
        String.format("  %s void method() { }", modifiers),
        "}"));

    assertThat(diagnostics.getDiagnostics(), Matchers.empty());
    assertEquals(
        expectedFlags,
        AccessFlags.getAccessFlags(
            findMethod("method", elements.getTypeElement("Foo"))));
  }

  private void testFieldFlags(String modifiers, int expectedFlags) throws IOException {
    compile(Joiner.on('\n').join(
        "class Foo {",
        String.format("  %s int field = 0;", modifiers),
        "}"));

    assertThat(diagnostics.getDiagnostics(), Matchers.empty());
    assertEquals(
        expectedFlags,
        AccessFlags.getAccessFlags(
            findField("field", elements.getTypeElement("Foo"))));
  }
}
