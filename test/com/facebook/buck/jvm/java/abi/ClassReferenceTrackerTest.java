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

import static org.junit.Assert.assertThat;

import com.facebook.buck.jvm.java.testutil.compiler.Classes;
import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTestRunner;
import com.facebook.buck.jvm.java.testutil.compiler.TestCompiler;
import java.io.IOException;
import java.util.SortedSet;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.objectweb.asm.ClassReader;

@RunWith(CompilerTreeApiTestRunner.class)
public class ClassReferenceTrackerTest {
  @Rule public TestCompiler testCompiler = new TestCompiler();
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void testRecordsSuperclassesAndInterfaces() throws IOException {
    assertThat(
        getReferencedClassNames(
            "abstract class Foo extends java.util.ArrayList implements CharSequence, Runnable {",
            "}"),
        Matchers.containsInAnyOrder(
            "java/util/ArrayList", "java/lang/Runnable", "java/lang/CharSequence"));
  }

  @Test
  public void testRecordsMethodReturnTypes() throws IOException {
    assertThat(
        getReferencedClassNames("class Foo {", "  String foo() { return null; }", "}"),
        Matchers.containsInAnyOrder("java/lang/String", "java/lang/Object"));
  }

  @Test
  public void testRecordsMethodParameterTypes() throws IOException {
    assertThat(
        getReferencedClassNames("class Foo {", "  void foo(String s) { }", "}"),
        Matchers.containsInAnyOrder("java/lang/String", "java/lang/Object"));
  }

  @Test
  public void testRecordsMethodThrowsTypes() throws IOException {
    assertThat(
        getReferencedClassNames("class Foo {", "  void foo() throws Exception { }", "}"),
        Matchers.containsInAnyOrder("java/lang/Exception", "java/lang/Object"));
  }

  @Test
  public void testRecordsFieldTypes() throws IOException {
    assertThat(
        getReferencedClassNames("class Foo {", "  String s;", "}"),
        Matchers.containsInAnyOrder("java/lang/String", "java/lang/Object"));
  }

  @Test
  public void testRecordsAnnotationsOnClasses() throws IOException {
    assertThat(
        getReferencedClassNames("@Deprecated", "class Foo { }"),
        Matchers.containsInAnyOrder("java/lang/Deprecated", "java/lang/Object"));
  }

  @Test
  public void testRecordsTypeAnnotationsOnClasses() throws IOException {
    assertThat(
        getReferencedClassNames(
            "import java.lang.annotation.*;",
            "abstract class Foo implements @Bar CharSequence { }",
            "@Target(ElementType.TYPE_USE)",
            "@interface Bar { }"),
        Matchers.containsInAnyOrder("Bar", "java/lang/CharSequence", "java/lang/Object"));
  }

  @Test
  public void testRecordsAnnotationsOnMethods() throws IOException {
    assertThat(
        getReferencedClassNames("class Foo {", "  @Deprecated", "  void foo() { }", "}"),
        Matchers.containsInAnyOrder("java/lang/Deprecated", "java/lang/Object"));
  }

  @Test
  public void testRecordsAnnotationsOnMethodParameters() throws IOException {
    assertThat(
        getReferencedClassNames("class Foo {", "  void foo(@Deprecated Object parameter) { }", "}"),
        Matchers.containsInAnyOrder("java/lang/Deprecated", "java/lang/Object"));
  }

  @Test
  public void testRecordsTypeAnnotationsOnMethodParameters() throws IOException {
    assertThat(
        getReferencedClassNames(
            "import java.lang.annotation.*;",
            "class Foo {",
            "  void foo(@Bar CharSequence c) { }",
            "}",
            "@Target(ElementType.TYPE_USE)",
            "@interface Bar { }"),
        Matchers.containsInAnyOrder("Bar", "java/lang/CharSequence", "java/lang/Object"));
  }

  @Test
  public void testRecordsAnnotationsOnFields() throws IOException {
    assertThat(
        getReferencedClassNames("class Foo {", "  @Deprecated", "  Object field;", "}"),
        Matchers.containsInAnyOrder("java/lang/Deprecated", "java/lang/Object"));
  }

  @Test
  public void testRecordsTypeAnnotationsOnFields() throws IOException {
    assertThat(
        getReferencedClassNames(
            "import java.lang.annotation.*;",
            "class Foo {",
            "  @Bar CharSequence field;",
            "}",
            "@Target(ElementType.TYPE_USE)",
            "@interface Bar { }"),
        Matchers.containsInAnyOrder("Bar", "java/lang/CharSequence", "java/lang/Object"));
  }

  @Test
  public void testRecordsGenericSuperclassTypeArguments() throws IOException {
    assertThat(
        getReferencedClassNames("class Foo extends java.util.HashMap<String, Integer> {", "}"),
        Matchers.containsInAnyOrder("java/util/HashMap", "java/lang/String", "java/lang/Integer"));
  }

  @Test
  public void testRecordsGenericInterfaceTypeArguments() throws IOException {
    assertThat(
        getReferencedClassNames(
            "abstract class Foo implements java.util.Map<String, Integer> {", "}"),
        Matchers.containsInAnyOrder(
            "java/util/Map", "java/lang/String", "java/lang/Integer", "java/lang/Object"));
  }

  @Test
  public void testRecordsGenericFieldTypeArguments() throws IOException {
    assertThat(
        getReferencedClassNames("class Foo {", "  java.util.HashMap<String, Integer> field;", "}"),
        Matchers.containsInAnyOrder(
            "java/util/HashMap", "java/lang/String", "java/lang/Integer", "java/lang/Object"));
  }

  @Test
  public void testRecordsGenericReturnTypeTypeArguments() throws IOException {
    assertThat(
        getReferencedClassNames(
            "class Foo {" + "  java.util.HashMap<String, Integer> foo() { return null; }", "}"),
        Matchers.containsInAnyOrder(
            "java/util/HashMap", "java/lang/String", "java/lang/Integer", "java/lang/Object"));
  }

  @Test
  public void testRecordsGenericParameterTypeArguments() throws IOException {
    assertThat(
        getReferencedClassNames(
            "class Foo {" + "  void foo(java.util.HashMap<String, Integer> m) { }", "}"),
        Matchers.containsInAnyOrder(
            "java/util/HashMap", "java/lang/String", "java/lang/Integer", "java/lang/Object"));
  }

  @Test
  public void testRecordsArrayElementTypes() throws IOException {
    assertThat(
        getReferencedClassNames("class Foo {", "  String[] s;", "}"),
        Matchers.containsInAnyOrder("java/lang/String", "java/lang/Object"));
  }

  @Test
  public void testRecordsGenericArrayElementTypeArgumentTypes() throws IOException {
    assertThat(
        getReferencedClassNames("class Foo {", "  java.util.List<String>[] s;", "}"),
        Matchers.containsInAnyOrder("java/util/List", "java/lang/String", "java/lang/Object"));
  }

  @Test
  public void testRecordsClassTypeBoundTypes() throws IOException {
    assertThat(
        getReferencedClassNames("class Foo<T extends java.util.ArrayList> {", "}"),
        Matchers.containsInAnyOrder("java/util/ArrayList", "java/lang/Object"));
  }

  @Test
  public void testRecordsInterfaceTypeBoundTypes() throws IOException {
    assertThat(
        getReferencedClassNames("abstract class Foo<T extends CharSequence> {", "}"),
        Matchers.containsInAnyOrder("java/lang/CharSequence", "java/lang/Object"));
  }

  @Test
  public void testRecordsWildcardBoundTypes() throws IOException {
    assertThat(
        getReferencedClassNames(
            "class Foo {" + "  java.util.HashMap<? extends CharSequence, ? super Integer> field;",
            "}"),
        Matchers.containsInAnyOrder(
            "java/util/HashMap",
            "java/lang/CharSequence",
            "java/lang/Integer",
            "java/lang/Object"));
  }

  @Test
  public void testRecordsAnnotationEnumValues() throws IOException {
    assertThat(
        getReferencedClassNames(
            "import java.lang.annotation.*;",
            "@Retention(RetentionPolicy.RUNTIME)",
            "@interface Foo {",
            "}"),
        Matchers.containsInAnyOrder(
            "java/lang/annotation/Retention",
            "java/lang/annotation/RetentionPolicy",
            "java/lang/annotation/Annotation",
            "java/lang/Object"));
  }

  @Test
  public void testRecordsAnnotationArrayValues() throws IOException {
    assertThat(
        getReferencedClassNames(
            "import java.lang.annotation.*;",
            "@Target({ElementType.FIELD, ElementType.METHOD})",
            "@interface Foo {",
            "}"),
        Matchers.containsInAnyOrder(
            "java/lang/annotation/Target",
            "java/lang/annotation/ElementType",
            "java/lang/annotation/Annotation",
            "java/lang/Object"));
  }

  @Test
  public void testRecordsAnnotationAnnotationValues() throws IOException {
    assertThat(
        getReferencedClassNames(
            "@Bar(@Baz)",
            "class Foo {",
            "}",
            "@interface Bar {",
            "  Baz value();",
            "}",
            "@interface Baz {",
            "}"),
        Matchers.containsInAnyOrder("Bar", "Baz", "java/lang/Object"));
  }

  @Test
  public void testDoesNotRecordTypeVariables() throws IOException {
    assertThat(
        getReferencedClassNames(
            "class Foo {", "  <T extends Exception, U> void foo() throws T { }", "}"),
        Matchers.containsInAnyOrder("java/lang/Exception", "java/lang/Object"));
  }

  private SortedSet<String> getReferencedClassNames(String... sourceLines) throws IOException {
    testCompiler.addSourceFileContents("Foo.java", sourceLines);
    testCompiler.compile();

    Classes classes = testCompiler.getClasses();
    ClassReferenceTracker tracker = new ClassReferenceTracker();
    classes.acceptClassVisitor(
        "Foo", ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES, tracker);

    return tracker.getReferencedClassNames();
  }
}
