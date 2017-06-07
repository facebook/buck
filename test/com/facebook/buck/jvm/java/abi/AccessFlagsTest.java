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

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTest;
import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTestRunner;
import com.google.common.base.Joiner;
import java.io.IOException;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.objectweb.asm.Opcodes;

@RunWith(CompilerTreeApiTestRunner.class)
public class AccessFlagsTest extends CompilerTreeApiTest {

  private AccessFlags accessFlags;

  @Test
  public void testPublicFlagOnField() throws IOException {
    testFieldFlags("public", Opcodes.ACC_PUBLIC);
  }

  @Test
  public void testPublicFlagOnMethod() throws IOException {
    testMethodFlags("public", Opcodes.ACC_PUBLIC);
  }

  @Test
  public void testPublicFlagOnClass() throws IOException {
    testClassFlags("public", Opcodes.ACC_PUBLIC);
  }

  @Test
  public void testProtectedFlagOnField() throws IOException {
    testFieldFlags("protected", Opcodes.ACC_PROTECTED);
  }

  @Test
  public void testProtectedFlagOnMethod() throws IOException {
    testMethodFlags("protected", Opcodes.ACC_PROTECTED);
  }

  @Test
  public void testPrivateFlagOnField() throws IOException {
    testFieldFlags("private", Opcodes.ACC_PRIVATE);
  }

  @Test
  public void testPrivateFlagOnMethod() throws IOException {
    testMethodFlags("private", Opcodes.ACC_PRIVATE);
  }

  @Test
  public void testNoFlagForDefaultVisibilityOnField() throws IOException {
    testFieldFlags("", 0);
  }

  @Test
  public void testNoFlagForDefaultVisibilityOnMethod() throws IOException {
    testMethodFlags("", 0);
  }

  @Test
  public void testNoFlagForDefaultVisibilityOnClass() throws IOException {
    testClassFlags("", 0);
  }

  @Test
  public void testNoFlagForInterfaceDefaultMethod() throws IOException {
    compile(Joiner.on('\n').join("interface Foo {", "  default void foo() { }", "}"));

    assertEquals(
        Opcodes.ACC_PUBLIC,
        accessFlags.getAccessFlags(findMethod("foo", elements.getTypeElement("Foo"))));
  }

  @Test
  public void testStaticFlagOnField() throws IOException {
    testFieldFlags("static", Opcodes.ACC_STATIC);
  }

  @Test
  public void testStaticFlagOnMethod() throws IOException {
    testMethodFlags("static", Opcodes.ACC_STATIC);
  }

  @Test
  public void testStaticFlagOnClass() throws IOException {
    testTypeFlags(
        Joiner.on('\n').join("class Foo {", "  static class Inner { }", "}"),
        "Foo.Inner",
        Opcodes.ACC_STATIC | Opcodes.ACC_SUPER);
  }

  @Test
  public void testFinalFlagOnField() throws IOException {
    testFieldFlags("final", Opcodes.ACC_FINAL);
  }

  @Test
  public void testFinalFlagOnMethod() throws IOException {
    testMethodFlags("final", Opcodes.ACC_FINAL);
  }

  @Test
  public void testFinalFlagOnClass() throws IOException {
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
  public void testAbstractFlagOnClass() throws IOException {
    testClassFlags("abstract", Opcodes.ACC_ABSTRACT);
  }

  @Test
  public void testAbstractFlagOnMethod() throws IOException {
    compile(Joiner.on('\n').join("abstract class Foo {", "  abstract void foo();", "}"));
    assertEquals(
        Opcodes.ACC_ABSTRACT,
        accessFlags.getAccessFlags(findMethod("foo", elements.getTypeElement("Foo"))));
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
    compile(Joiner.on('\n').join("class Foo {", "  native void method();", "}"));

    assertEquals(
        Opcodes.ACC_NATIVE,
        accessFlags.getAccessFlags(findMethod("method", elements.getTypeElement("Foo"))));
  }

  @Test
  public void testMultipleFlagsOnMethod() throws IOException {
    testMethodFlags("public static", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
  }

  @Test
  public void testMultipleFlagsOnField() throws IOException {
    testFieldFlags("public static", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
  }

  @Test
  public void testVarArgsFlag() throws IOException {
    compile(Joiner.on('\n').join("class Foo {", "  void method(String... s) { }", "}"));

    assertEquals(
        Opcodes.ACC_VARARGS,
        accessFlags.getAccessFlags(findMethod("method", elements.getTypeElement("Foo"))));
  }

  @Test
  public void testDeprecatedPseudoFlagOnField() throws IOException {
    testFieldFlags("@Deprecated", Opcodes.ACC_DEPRECATED);
  }

  @Test
  public void testDeprecatedPseudoFlagOnMethod() throws IOException {
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
    testTypeFlags("interface Foo { }", "Foo", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT);
  }

  @Test
  public void testEnumTypeFlags() throws IOException {
    testTypeFlags(
        "enum Foo { Item }", "Foo", Opcodes.ACC_ENUM | Opcodes.ACC_SUPER | Opcodes.ACC_FINAL);
  }

  /**
   * The {@link javax.lang.model.element.TypeElement} for an abstract Enum will *sometimes* report
   * that the enum is abstract, but not in all the cases where ACC_ABSTRACT needs to appear in the
   * class file. For this and other reasons we just never use the flag.
   */
  @Test
  public void testEnumAbstractFlagIsRemoved() throws IOException {
    testTypeFlags(
        Joiner.on('\n')
            .join(
                "enum Foo {",
                "  Value {",
                "    int get() { return 3; }",
                "  };",
                "  abstract int get();",
                "}"),
        "Foo",
        Opcodes.ACC_ENUM | Opcodes.ACC_SUPER);
  }

  @Test
  public void testEnumVarFlags() throws IOException {
    compile("enum Foo { Item }");
    assertEquals(
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
        accessFlags.getAccessFlags(findField("Item", elements.getTypeElement("Foo"))));
  }

  private void testClassFlags(String modifiers, int expectedFlags) throws IOException {
    testTypeFlags(
        String.format("%s class Foo { }", modifiers), "Foo", expectedFlags | Opcodes.ACC_SUPER);
  }

  private void testTypeFlags(String content, String typeName, int expectedFlags)
      throws IOException {
    compile(content);
    assertNoErrors();
    assertEquals(expectedFlags, accessFlags.getAccessFlags(elements.getTypeElement(typeName)));
  }

  private void testMethodFlags(String modifiers, int expectedFlags) throws IOException {
    compile(
        Joiner.on('\n')
            .join("class Foo {", String.format("  %s void method() { }", modifiers), "}"));

    assertNoErrors();
    assertEquals(
        expectedFlags,
        accessFlags.getAccessFlags(findMethod("method", elements.getTypeElement("Foo"))));
  }

  private void testFieldFlags(String modifiers, int expectedFlags) throws IOException {
    compile(
        Joiner.on('\n').join("class Foo {", String.format("  %s int field = 0;", modifiers), "}"));

    assertNoErrors();
    assertEquals(
        expectedFlags,
        accessFlags.getAccessFlags(findField("field", elements.getTypeElement("Foo"))));
  }

  @Override
  protected void initCompiler(Map<String, String> fileNamesToContents) throws IOException {
    super.initCompiler(fileNamesToContents);
    accessFlags = new AccessFlags(elements);
  }
}
