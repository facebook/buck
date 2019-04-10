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

import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTestRunner;
import com.facebook.buck.jvm.java.testutil.compiler.TestCompiler;
import com.google.common.base.Joiner;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import javax.annotation.processing.Completion;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiTestRunner.class)
public class TreeBackedProcessorWrapperTest {
  @Rule public TestCompiler testCompiler = new TestCompiler();

  private ProcessingEnvironment processingEnv;
  private Elements elements;
  private Messager messager;

  @Test
  public void testSourceAbiOptionPresent() throws IOException {
    runTestProcessor(
        (annotations, roundEnv) -> {
          assertTrue(
              Boolean.valueOf(
                  processingEnv
                      .getOptions()
                      .getOrDefault("com.facebook.buck.java.generating_abi", "false")));
          return false;
        });
  }

  @Test
  public void testElementUtilsIsWrapped() throws IOException {
    runTestProcessor(
        (annotations, roundEnv) -> {
          assertTrue(processingEnv.getElementUtils() instanceof TreeBackedElements);
          return false;
        });
  }

  @Test
  public void testTypeUtilsIsWrapped() throws IOException {
    runTestProcessor(
        (annotations, roundEnv) -> {
          assertTrue(processingEnv.getTypeUtils() instanceof TreeBackedTypes);
          return false;
        });
  }

  @Test
  public void testAnnotationsAreWrapped() throws IOException {
    runTestProcessor(
        (annotations, roundEnv) -> {
          if (!roundEnv.processingOver()) {
            TreeBackedTypeElement annotationType =
                (TreeBackedTypeElement) elements.getTypeElement("com.example.buck.FooAnno");

            assertThat(annotations, Matchers.contains(annotationType));
          } else {
            assertThat(annotations, Matchers.empty());
          }

          return false;
        });
  }

  @Test
  public void testRootElementsAreWrapped() throws IOException {
    runTestProcessor(
        (annotations, roundEnv) -> {
          if (!roundEnv.processingOver()) {
            TreeBackedTypeElement annotationType =
                (TreeBackedTypeElement) elements.getTypeElement("com.example.buck.FooAnno");
            TreeBackedTypeElement fooType =
                (TreeBackedTypeElement) elements.getTypeElement("com.example.buck.Foo");

            assertThat(
                roundEnv.getRootElements(), Matchers.containsInAnyOrder(annotationType, fooType));
          } else {
            assertThat(roundEnv.getRootElements(), Matchers.empty());
          }

          return false;
        });
  }

  @Test
  public void testGetElementsAnnotatedWith() throws IOException {
    runTestProcessor(
        (annotations, roundEnv) -> {
          TreeBackedTypeElement annotationType =
              (TreeBackedTypeElement) elements.getTypeElement("com.example.buck.FooAnno");
          TreeBackedTypeElement fooType =
              (TreeBackedTypeElement) elements.getTypeElement("com.example.buck.Foo");

          Set<? extends Element> annotatedElements =
              roundEnv.getElementsAnnotatedWith(annotationType);

          if (!roundEnv.processingOver()) {
            assertThat(annotatedElements, Matchers.contains(fooType));
          } else {
            assertThat(annotatedElements, Matchers.empty());
          }
          return false;
        });
  }

  @Test
  public void testElementMessager() throws IOException {
    testCompiler.setAllowCompilationErrors(true);
    runTestProcessor(
        (annotations, roundEnv) -> {
          messager.printMessage(
              Diagnostic.Kind.ERROR, "Foo", elements.getTypeElement("com.example.buck.Foo"));
          return false;
        });

    assertThat(
        testCompiler.getDiagnosticMessages().stream()
            .map(message -> message.substring(message.indexOf("Foo.java")))
            .collect(Collectors.toList()),
        Matchers.contains(
            Joiner.on('\n').join("Foo.java:3: error: Foo", "public class Foo {}", "       ^")));
  }

  private void runTestProcessor(
      BiFunction<Set<? extends TypeElement>, RoundEnvironment, Boolean> processMethod)
      throws IOException {
    testCompiler.useFrontendOnlyJavacTask();
    testCompiler.addSourceFileContents(
        "Foo.java",
        "package com.example.buck;",
        "@FooAnno",
        "public class Foo {}",
        "@interface FooAnno {}");

    testCompiler.setProcessors(
        Collections.singletonList(
            new Processor() {
              @Override
              public Set<String> getSupportedOptions() {
                return Collections.emptySet();
              }

              @Override
              public Set<String> getSupportedAnnotationTypes() {
                return Collections.singleton("com.example.buck.FooAnno");
              }

              @Override
              public SourceVersion getSupportedSourceVersion() {
                return SourceVersion.RELEASE_8;
              }

              @Override
              public void init(ProcessingEnvironment processingEnv) {
                TreeBackedProcessorWrapperTest.this.processingEnv = processingEnv;
                elements = processingEnv.getElementUtils();
                messager = processingEnv.getMessager();
              }

              @Override
              public Iterable<? extends Completion> getCompletions(
                  Element element,
                  AnnotationMirror annotation,
                  ExecutableElement member,
                  String userText) {
                fail("Should never be called");
                return null;
              }

              @Override
              public boolean process(
                  Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
                return processMethod.apply(annotations, roundEnv);
              }
            }));

    testCompiler.enter();
  }
}
