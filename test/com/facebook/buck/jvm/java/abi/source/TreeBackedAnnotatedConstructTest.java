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

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiParameterized;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.MirroredTypesException;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiParameterized.class)
public class TreeBackedAnnotatedConstructTest extends CompilerTreeApiParameterizedTest {
  @Test
  public void testGetAnnotationForStringValue() throws IOException {
    compile(Joiner.on('\n').join("@SuppressWarnings(\"42\")", "public class Foo {", "}"));

    SuppressWarnings anno = elements.getTypeElement("Foo").getAnnotation(SuppressWarnings.class);
    assertThat(anno.value(), Matchers.arrayContaining("42"));
  }

  @Test
  public void testGetAnnotationsForStringValue() throws IOException {
    compile(Joiner.on('\n').join("@SuppressWarnings(\"42\")", "public class Foo {", "}"));

    SuppressWarnings[] annos =
        elements.getTypeElement("Foo").getAnnotationsByType(SuppressWarnings.class);
    assertEquals(1, annos.length);
    assertThat(annos[0].value(), Matchers.arrayContaining("42"));
  }

  @Test
  public void testGetAnnotationWithSingleClass() throws Exception {
    testCompiler.addClasspathFileContents(
        "AnnotationWithSingleClassForTreeBackedTest.java",
        AnnotationWithSingleClassForTreeBackedTest.SOURCE_CODE);

    if (!testingTrees()) {
      testCompiler.addClasspathFileContents("Bar.java", "public class Bar {}");
    }

    initCompiler(
        ImmutableMap.of(
            "Foo.java",
            Joiner.on('\n')
                .join(
                    "@com.facebook.buck.jvm.java.abi.source.AnnotationWithSingleClassForTreeBackedTest(single = Bar.class)",
                    "public class Foo {",
                    "}")));
    testCompiler.setProcessors(
        Collections.singletonList(
            new ProcessorCallingGetAnnotation<>(AnnotationWithSingleClassForTreeBackedTest.class)));

    testCompiler.parse();
    testCompiler.enter();
  }

  @Test
  public void testGetAnnotationWithClassArray() throws Exception {
    testCompiler.addClasspathFileContents(
        "AnnotationWithClassArrayForTreeBackedTest.java",
        AnnotationWithClassArrayForTreeBackedTest.SOURCE_CODE);

    if (!testingTrees()) {
      testCompiler.addClasspathFileContents("Bar.java", "public class Bar {}");
      testCompiler.addClasspathFileContents("Baz.java", "public class Baz {}");
    }

    initCompiler(
        ImmutableMap.of(
            "Foo.java",
            Joiner.on('\n')
                .join(
                    "@com.facebook.buck.jvm.java.abi.source.AnnotationWithClassArrayForTreeBackedTest(multi = {Bar.class, Baz.class})",
                    "public class Foo {",
                    "}")));
    testCompiler.setProcessors(
        Collections.singletonList(
            new ProcessorCallingGetAnnotation<>(AnnotationWithClassArrayForTreeBackedTest.class)));

    testCompiler.parse();
    testCompiler.enter();
  }

  @Test
  public void testGetAnnotationAndGetFieldWithSingleClass() throws Exception {
    testCompiler.addClasspathFileContents(
        "AnnotationWithSingleClassForTreeBackedTest.java",
        AnnotationWithSingleClassForTreeBackedTest.SOURCE_CODE);

    if (!testingTrees()) {
      testCompiler.addClasspathFileContents("Bar.java", "public class Bar {}");
    }

    initCompiler(
        ImmutableMap.of(
            "Foo.java",
            Joiner.on('\n')
                .join(
                    "@com.facebook.buck.jvm.java.abi.source.AnnotationWithSingleClassForTreeBackedTest(single = Bar.class)",
                    "public class Foo {",
                    "}")));
    testCompiler.setProcessors(
        Collections.singletonList(
            new ProcessorCallingGetAnnotation<>(
                AnnotationWithSingleClassForTreeBackedTest.class,
                (a) -> {
                  try {
                    a.single();
                  } catch (MirroredTypeException e) { // NOPMD: Expected.
                    // NOPMD: No problem.
                  }
                })));

    testCompiler.parse();
    testCompiler.enter();
  }

  @Test
  public void testGetAnnotationAndGetFieldWithClassArray() throws Exception {
    testCompiler.addClasspathFileContents(
        "AnnotationWithClassArrayForTreeBackedTest.java",
        AnnotationWithClassArrayForTreeBackedTest.SOURCE_CODE);

    if (!testingTrees()) {
      testCompiler.addClasspathFileContents("Bar.java", "public class Bar {}");
      testCompiler.addClasspathFileContents("Baz.java", "public class Baz {}");
    }

    initCompiler(
        ImmutableMap.of(
            "Foo.java",
            Joiner.on('\n')
                .join(
                    "@com.facebook.buck.jvm.java.abi.source.AnnotationWithClassArrayForTreeBackedTest(multi = {Bar.class, Baz.class})",
                    "public class Foo {",
                    "}")));
    testCompiler.setProcessors(
        Collections.singletonList(
            new ProcessorCallingGetAnnotation<>(
                AnnotationWithClassArrayForTreeBackedTest.class,
                (a) -> {
                  try {
                    a.multi();
                  } catch (MirroredTypesException e) { // NOPMD: Expected.
                    // No problem.
                  }
                })));

    testCompiler.parse();
    try {
      testCompiler.enter();
      if (testingTrees()) {
        fail("Expected javac to fail to load this annotation");
      }
    } catch (Throwable e) {
      if (!testingTrees()) {
        // Not expecting this.  Fail.
        throw e;
      }

      // Make sure the exception is at least informative.
      Throwable realException = e;
      while (realException.getCause() != realException
          && (realException instanceof InvocationTargetException
              || realException instanceof AssertionError)) {
        realException = realException.getCause();
      }
      assertThat(realException.getMessage(), containsString("ForTreeBackedTest"));
      assertThat(realException.getMessage(), containsString("Foo"));
      assertThat(realException.getMessage(), containsString("Bar"));
      assertThat(realException.getMessage(), containsString("Baz"));
    }
  }

  class ProcessorCallingGetAnnotation<T extends Annotation> extends AbstractProcessor {
    private final Class<T> annotationClass;
    private final Consumer<T> consumer;

    ProcessorCallingGetAnnotation<T> create(Class<T> annotationClass) {
      return new ProcessorCallingGetAnnotation<T>(annotationClass);
    }

    ProcessorCallingGetAnnotation(Class<T> annotationClass) {
      this(annotationClass, (a) -> {});
    }

    ProcessorCallingGetAnnotation(Class<T> annotationClass, Consumer<T> consumer) {
      this.annotationClass = annotationClass;
      this.consumer = consumer;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
      return Collections.singleton(annotationClass.getName());
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (annotations.isEmpty()) {
        return false;
      }
      TypeElement annotationType = annotations.iterator().next();
      assertNameEquals(annotationClass.getName(), annotationType.getQualifiedName());
      Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(annotationType);
      for (Element element : elements) {
        T annotation = element.getAnnotation(annotationClass);
        consumer.accept(annotation);
      }
      return false;
    }
  }
}
