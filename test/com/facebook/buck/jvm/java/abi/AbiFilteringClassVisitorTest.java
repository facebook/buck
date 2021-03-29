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

package com.facebook.buck.jvm.java.abi;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

public class AbiFilteringClassVisitorTest {
  private ClassVisitor mockVisitor;
  private AbiFilteringClassVisitor filteringVisitor;

  @Before
  public void setUp() {
    mockVisitor = createMock(ClassVisitor.class);
    filteringVisitor =
        new AbiFilteringClassVisitor(mockVisitor, ImmutableList.of(), ImmutableSet.of(), false);
  }

  @Test
  public void testExcludesPrivateFields() {
    testExcludesFieldWithAccess(Opcodes.ACC_PRIVATE);
  }

  @Test
  public void testExcludesPrivateStaticFields() {
    testExcludesFieldWithAccess(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC);
  }

  @Test
  public void testExcludesSyntheticFields() {
    testExcludesFieldWithAccess(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC);
  }

  @Test
  public void testIncludesPackageFields() {
    testIncludesFieldWithAccess(0);
  }

  @Test
  public void testIncludesPackageStaticFields() {
    testIncludesFieldWithAccess(Opcodes.ACC_STATIC);
  }

  @Test
  public void testIncludesPublicFields() {
    testIncludesFieldWithAccess(Opcodes.ACC_PUBLIC);
  }

  @Test
  public void testIncludesProtectedFields() {
    testIncludesFieldWithAccess(Opcodes.ACC_PROTECTED);
  }

  @Test
  public void testNotConfusedByOtherFieldAccessFlagsIncluding() {
    testIncludesFieldWithAccess(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE);
  }

  @Test
  public void testNotConfusedByOtherFieldAccessFlagsExcluding() {
    testExcludesFieldWithAccess(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE);
  }

  @Test
  public void testExcludesPrivateMethods() {
    testExcludesMethodWithAccess(Opcodes.ACC_PRIVATE);
  }

  @Test
  public void testIncludesPrivateMethodsWhenRetained() {
    filteringVisitor =
        new AbiFilteringClassVisitor(mockVisitor, ImmutableList.of("foo"), ImmutableSet.of(), false);
    testIncludesMethodWithAccess(Opcodes.ACC_PRIVATE);
  }

  @Test
  public void testIncludesPackageMethods() {
    testIncludesMethodWithAccess(Opcodes.ACC_PUBLIC);
  }

  @Test
  public void testIncludesProtectedMethods() {
    testIncludesMethodWithAccess(Opcodes.ACC_PUBLIC);
  }

  @Test
  public void testIncludesPublicMethods() {
    testIncludesMethodWithAccess(Opcodes.ACC_PUBLIC);
  }

  @Test
  public void testExcludesSyntheticMethods() {
    testExcludesMethodWithAccess(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC);
  }

  @Test
  public void testNotConfusedByOtherMethodAccessFlagsIncluding() {
    testIncludesMethodWithAccess(
        Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNCHRONIZED);
  }

  @Test
  public void testNotConfusedByOtherMethodAccessFlagsExcluding() {
    testExcludesMethodWithAccess(
        Opcodes.ACC_PRIVATE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNCHRONIZED);
  }

  @Test
  public void testExcludesStaticInitializers() {
    testExcludesMethodWithAccess(Opcodes.ACC_STATIC, "<clinit>");
  }

  @Test
  public void testAlwaysVisitsClassNode() {
    visitClass(mockVisitor, "Foo");
    replay(mockVisitor);
    visitClass(filteringVisitor, "Foo");
    verify(mockVisitor);
  }

  @Test
  public void testIncludesInnerClassEntryForClassItself() {
    visitClass(mockVisitor, "Foo$Inner");
    mockVisitor.visitInnerClass("Foo$Inner", "Foo", "Inner", Opcodes.ACC_PUBLIC);
    replay(mockVisitor);

    visitClass(filteringVisitor, "Foo$Inner");
    filteringVisitor.visitInnerClass("Foo$Inner", "Foo", "Inner", Opcodes.ACC_PUBLIC);
    verify(mockVisitor);
  }

  @Test
  public void testIncludesInnerClassEntryForInnerClass() {
    visitClass(mockVisitor, "Foo");
    mockVisitor.visitInnerClass("Foo$Inner", "Foo", "Inner", Opcodes.ACC_PUBLIC);
    replay(mockVisitor);

    visitClass(filteringVisitor, "Foo");
    filteringVisitor.visitInnerClass("Foo$Inner", "Foo", "Inner", Opcodes.ACC_PUBLIC);
    verify(mockVisitor);
  }

  @Test
  public void testIncludesInnerClassEntryForReferencedOtherClassInnerClass() {
    filteringVisitor =
        new AbiFilteringClassVisitor(mockVisitor, ImmutableList.of(), ImmutableSet.of("Bar$Inner"), false);

    visitClass(mockVisitor, "Foo");
    mockVisitor.visitInnerClass("Bar$Inner", "Bar", "Inner", Opcodes.ACC_PUBLIC);
    replay(mockVisitor);

    visitClass(filteringVisitor, "Foo");
    filteringVisitor.visitInnerClass("Bar$Inner", "Bar", "Inner", Opcodes.ACC_PUBLIC);
    verify(mockVisitor);
  }

  @Test
  public void testExcludesInnerClassEntryForUnreferencedOtherClassInnerClass() {
    visitClass(mockVisitor, "Foo");
    replay(mockVisitor);

    visitClass(filteringVisitor, "Foo");
    filteringVisitor.visitInnerClass("Bar$Inner", "Bar", "Inner", Opcodes.ACC_PUBLIC);
    verify(mockVisitor);
  }

  @Test
  public void testIncludesPrivateInnerClassesForNow() {
    visitClass(mockVisitor, "Foo");
    mockVisitor.visitInnerClass("Foo$Inner", "Foo", "Inner", Opcodes.ACC_PRIVATE);
    replay(mockVisitor);

    visitClass(filteringVisitor, "Foo");
    filteringVisitor.visitInnerClass("Foo$Inner", "Foo", "Inner", Opcodes.ACC_PRIVATE);
    verify(mockVisitor);
  }

  @Test
  public void testExcludesSyntheticInnerClasses() {
    visitClass(mockVisitor, "Foo");
    replay(mockVisitor);

    visitClass(filteringVisitor, "Foo");
    filteringVisitor.visitInnerClass(
        "Foo$Inner", "Foo", "Inner", Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC);
    verify(mockVisitor);
  }

  @Test
  public void testExcludesAnonymousInnerClasses() {
    visitClass(mockVisitor, "Foo");
    replay(mockVisitor);

    visitClass(filteringVisitor, "Foo");
    filteringVisitor.visitInnerClass("Foo$1", null, null, 0);
    verify(mockVisitor);
  }

  @Test
  public void testExcludesLocalClasses() {
    visitClass(mockVisitor, "Foo");
    replay(mockVisitor);

    visitClass(filteringVisitor, "Foo");
    filteringVisitor.visitInnerClass("Foo$1Bar", null, "Bar", 0);
    verify(mockVisitor);
  }

  private static void visitClass(ClassVisitor cv, String name) {
    cv.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
  }

  private void testExcludesFieldWithAccess(int access) {
    testFieldWithAccess(access, false);
  }

  private void testIncludesFieldWithAccess(int access) {
    testFieldWithAccess(access, true);
  }

  private void testFieldWithAccess(int access, boolean shouldInclude) {
    if (shouldInclude) {
      expect(mockVisitor.visitField(access, "Foo", "I", null, null)).andReturn(null);
    }
    replay(mockVisitor);
    filteringVisitor.visitField(access, "Foo", "I", null, null);
    verify(mockVisitor);
  }

  private void testExcludesMethodWithAccess(int access) {
    testExcludesMethodWithAccess(access, "foo");
  }

  private void testIncludesMethodWithAccess(int access) {
    testIncludesMethodWithAccess(access, "foo");
  }

  private void testExcludesMethodWithAccess(int access, String name) {
    testMethodWithAccess(access, name, false);
  }

  private void testIncludesMethodWithAccess(int access, String name) {
    testMethodWithAccess(access, name, true);
  }

  private void testMethodWithAccess(int access, String name, boolean shouldInclude) {
    if (shouldInclude) {
      expect(mockVisitor.visitMethod(access, name, "()V", null, null)).andReturn(null);
    }
    replay(mockVisitor);
    filteringVisitor.visitMethod(access, name, "()V", null, null);
    verify(mockVisitor);
  }
}
