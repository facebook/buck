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

package com.facebook.buck.jvm.java.abi.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiParameterized;
import com.google.common.base.Joiner;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiParameterized.class)
public class TreeBackedAnnotationMirrorTest extends CompilerTreeApiParameterizedTest {
  @Test
  public void testAnnotationMirrorValue() throws IOException {
    compile(
        Joiner.on('\n').join("@FooHelper", "public class Foo {", "}", "@interface FooHelper {}"));

    AnnotationMirror a = elements.getTypeElement("Foo").getAnnotationMirrors().get(0);
    assertSameType(elements.getTypeElement("FooHelper").asType(), a.getAnnotationType());
  }

  @Test
  public void testSingleElementAnnotationMirrorValue() throws IOException {
    compile(
        Joiner.on('\n')
            .join(
                "@FooHelper(42)",
                "public class Foo {",
                "}",
                "@interface FooHelper {",
                "  int value();",
                "}"));

    AnnotationMirror a = elements.getTypeElement("Foo").getAnnotationMirrors().get(0);
    ExecutableElement keyElement = findMethod("value", elements.getTypeElement("FooHelper"));

    assertEquals(1, a.getElementValues().size());
    assertEquals(42, a.getElementValues().get(keyElement).getValue());
  }

  @Test
  public void testSingleElementExplicitAnnotationMirrorValue() throws IOException {
    compile(
        Joiner.on('\n')
            .join(
                "@FooHelper(number = 42)",
                "public class Foo {",
                "}",
                "@interface FooHelper {",
                "  int number();",
                "}"));

    AnnotationMirror a = elements.getTypeElement("Foo").getAnnotationMirrors().get(0);
    ExecutableElement keyElement = findMethod("number", elements.getTypeElement("FooHelper"));

    assertEquals(1, a.getElementValues().size());
    assertEquals(42, a.getElementValues().get(keyElement).getValue());
  }

  @Test
  public void testSingleElementArrayAnnotationMirrorValueWithSingleEntry() throws IOException {
    compile(
        Joiner.on('\n')
            .join(
                "@FooHelper(42)",
                "public class Foo {",
                "}",
                "@interface FooHelper {",
                "  int[] value();",
                "}"));

    AnnotationMirror a = elements.getTypeElement("Foo").getAnnotationMirrors().get(0);
    ExecutableElement keyElement = findMethod("value", elements.getTypeElement("FooHelper"));

    @SuppressWarnings("unchecked")
    List<AnnotationValue> values =
        (List<AnnotationValue>) a.getElementValues().get(keyElement).getValue();
    assertEquals(42, values.get(0).getValue());
    assertEquals(1, a.getElementValues().size());
    assertEquals(1, values.size());
  }

  @Test
  public void testMultiElementAnnotationMirrorValue() throws IOException {
    compile(
        Joiner.on('\n')
            .join(
                "@FooHelper(number=42, string=\"42\", doubleNumber=42.0)",
                "public class Foo {",
                "}",
                "@interface FooHelper {",
                "  double doubleNumber();",
                "  int number();",
                "  String string();",
                "}"));

    AnnotationMirror a = elements.getTypeElement("Foo").getAnnotationMirrors().get(0);

    assertThat(
        a.getElementValues().entrySet().stream()
            .flatMap(
                entry ->
                    Stream.of(
                        entry.getKey().getSimpleName().toString(), entry.getValue().getValue()))
            .collect(Collectors.toList()),
        Matchers.contains("number", 42, "string", "42", "doubleNumber", 42.0));
  }
}
