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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.jvm.java.plugin.adapter.BuckJavacPlugin;
import com.facebook.buck.jvm.java.plugin.adapter.BuckJavacTask;
import com.facebook.buck.jvm.java.plugin.adapter.TestTaskListener;
import com.facebook.buck.jvm.java.plugin.adapter.TestTaskListenerAdapter;
import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiParameterized;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.sun.source.tree.CompilationUnitTree;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiParameterized.class)
public class FrontendOnlyJavacTaskTest extends CompilerTreeApiParameterizedTest {
  @Test
  public void testParseReturnsAllCompilationUnits() throws IOException {
    initCompiler(
        ImmutableMap.of(
            "Foo.java",
            "public class Foo { }",
            "Bar.java",
            "public class Bar { }",
            "Baz.java",
            "public class Baz { }"));

    Iterable<? extends CompilationUnitTree> compilationUnits = testCompiler.parse();

    List<String> fileNames =
        RichStream.from(compilationUnits)
            .map(
                compilationUnit ->
                    Paths.get(compilationUnit.getSourceFile().getName()).getFileName().toString())
            .toImmutableList();

    assertThat(fileNames, Matchers.contains("Foo.java", "Bar.java", "Baz.java"));
  }

  @Test
  public void testEnterReturnsTopLevelTypesOnly() throws Exception {
    initCompiler(
        ImmutableMap.of(
            "Foo.java",
            Joiner.on('\n')
                .join(
                    "package com.facebook.foo;",
                    "public class Foo {",
                    "  private Runnable field = new Runnable() {",
                    "    public void run() { }",
                    "  };",
                    "  private void method() {",
                    "    class Local { };",
                    "  }",
                    "  private class Inner { }",
                    "}",
                    "class Extra { }"),
            "Bar.java",
            Joiner.on('\n').join("package com.facebook.bar;", "class Bar { }"),
            "package-info.java",
            "package com.facebook.foo;"));

    Iterable<? extends Element> actualElements = testCompiler.enter();
    assertThat(
        actualElements,
        Matchers.containsInAnyOrder(
            elements.getTypeElement("com.facebook.foo.Foo"),
            elements.getTypeElement("com.facebook.foo.Extra"),
            elements.getTypeElement("com.facebook.bar.Bar")));
  }

  @Test
  public void testTaskListenersGetEvents() throws Exception {
    assumeTrue(testingTrees());
    List<String> events = new ArrayList<>();

    initCompiler(ImmutableMap.of("Foo.java", "public class Foo { }"));

    testCompiler.addPlugin(
        new BuckJavacPlugin() {
          @Override
          public String getName() {
            return null;
          }

          @Override
          public void init(BuckJavacTask task, String... args) {
            TestTaskListenerAdapter.setTaskListener(
                task,
                new TestTaskListener() {
                  @Override
                  public void started(String event) {
                    events.add(String.format("%s started", event));
                  }

                  @Override
                  public void finished(String event) {
                    events.add(String.format("%s finished", event));
                  }
                });
          }
        });

    testCompiler.parse();

    assertThat(events, Matchers.contains("PARSE started", "PARSE finished"));
    events.clear();

    testCompiler.enter();
    assertThat(events, Matchers.contains("ENTER started", "ENTER finished"));
  }

  @Test
  public void testTaskListenersCanWorkWithElements() throws Exception {
    assumeTrue(testingTrees());
    AtomicBoolean listenerRan = new AtomicBoolean(false);

    initCompiler(ImmutableMap.of("Foo.java", "public class Foo { }"));

    Elements elements = testCompiler.getElements();

    testCompiler.addPlugin(
        new BuckJavacPlugin() {
          @Override
          public String getName() {
            return null;
          }

          @Override
          public void init(BuckJavacTask task, String... args) {
            TestTaskListenerAdapter.setTaskListener(
                task,
                new TestTaskListener() {
                  @Override
                  public void started(String event) {}

                  @Override
                  public void finished(String event) {
                    if (event.equals("ENTER")) {
                      TypeElement element = elements.getTypeElement("Foo");
                      assertNotNull(element);
                      listenerRan.set(true);
                    }
                  }
                });
          }
        });

    testCompiler.enter();

    assertTrue(listenerRan.get());
  }

  @Test
  public void testCompileRunsAllPhases() throws Exception {
    List<String> events = new ArrayList<>();

    initCompiler(ImmutableMap.of("Foo.java", "public class Foo { }"));

    TestTaskListenerAdapter.setTaskListener(
        testCompiler.getJavacTask(),
        new TestTaskListener() {
          @Override
          public void started(String event) {
            events.add(String.format("%s started", event));
          }

          @Override
          public void finished(String event) {
            events.add(String.format("%s finished", event));
          }
        });

    testCompiler.compile();

    String[] javacEvents = {
      "PARSE started",
      "PARSE finished",
      "ENTER started",
      "ENTER finished",
      "ANALYZE started",
      "ANALYZE finished",
      "GENERATE started",
      "GENERATE finished",
    };

    String[] treesEvents = {
      "PARSE started", "PARSE finished", "ENTER started", "ENTER finished",
    };

    assertThat(events, Matchers.contains(testingJavac() ? javacEvents : treesEvents));
  }

  @Test
  public void testAnnotationProcessorGetsNewElementsEachRound() throws Exception {
    AtomicBoolean processorRan = new AtomicBoolean(false);

    initCompiler(ImmutableMap.of("Foo.java", "public class Foo { }"));

    testCompiler.setProcessors(
        ImmutableList.of(
            new AbstractProcessor() {
              private Elements elementsFromEnvironment;
              private TypeElement element;

              @Override
              public Set<String> getSupportedAnnotationTypes() {
                return Collections.singleton("*");
              }

              @Override
              public synchronized void init(ProcessingEnvironment processingEnv) {
                super.init(processingEnv);
                elementsFromEnvironment = processingEnv.getElementUtils();
              }

              @Override
              public boolean process(
                  Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
                TypeElement newElement = elementsFromEnvironment.getTypeElement("Foo");
                assertNotNull(newElement);
                assertNotSame(newElement, element);
                element = newElement;

                processorRan.set(roundEnv.processingOver());

                return false;
              }
            }));

    testCompiler.compile();

    assertTrue(processorRan.get());
  }
}
