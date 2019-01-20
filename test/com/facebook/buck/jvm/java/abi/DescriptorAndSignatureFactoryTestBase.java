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

import static org.junit.Assert.fail;

import com.facebook.buck.jvm.java.testutil.compiler.Classes;
import com.facebook.buck.jvm.java.testutil.compiler.TestCompiler;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import org.junit.Rule;
import org.junit.runners.Parameterized;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

public class DescriptorAndSignatureFactoryTestBase {
  private static final String WITH_DEPS = "With Dependencies";

  private static final String WITHOUT_DEPS = "Without Dependencies";

  @Parameterized.Parameter public String testMode;

  @Parameterized.Parameters(name = "{0}")
  public static Object[] getParameters() {
    return new Object[] {WITH_DEPS, WITHOUT_DEPS};
  }

  @Rule public TestCompiler correctClassCompiler = new TestCompiler();
  @Rule public TestCompiler testCompiler = new TestCompiler();

  private Classes correctClasses;
  protected Elements elements;
  List<String> errors = new ArrayList<>();

  protected boolean isTestingWithDependencies() {
    return testMode == WITH_DEPS;
  }

  protected void test(TestRunnable r) throws Exception {
    // Always compile with dependencies, so that we have the correct output to compare to
    generateCorrectClassFiles();

    runTest(r);
  }

  private void generateCorrectClassFiles() throws IOException {
    correctClassCompiler.addSourceFile(getSourceFile("Foo.java"));
    correctClassCompiler.addClasspathSourceFile(getSourceFile("Dependency.java"));
    correctClassCompiler.addClasspathSourceFile(getSourceFile("DependencyException.java"));
    correctClassCompiler.addClasspathSourceFile(getSourceFile("DependencyInterface.java"));
    correctClassCompiler.compile();
    correctClasses = correctClassCompiler.getClasses();
  }

  private void runTest(TestRunnable r) throws Exception {
    testCompiler.addSourceFile(getSourceFile("Foo.java"));
    if (testMode == WITH_DEPS) {
      testCompiler.addClasspathSourceFile(getSourceFile("Dependency.java"));
      testCompiler.addClasspathSourceFile(getSourceFile("DependencyException.java"));
      testCompiler.addClasspathSourceFile(getSourceFile("DependencyInterface.java"));
    } else {
      testCompiler.useFrontendOnlyJavacTask();
    }

    testCompiler.setProcessors(
        Collections.singletonList(
            new AbstractProcessor() {
              @Override
              public Set<String> getSupportedOptions() {
                return Collections.emptySet();
              }

              @Override
              public Set<String> getSupportedAnnotationTypes() {
                return Collections.singleton("*");
              }

              @Override
              public SourceVersion getSupportedSourceVersion() {
                return SourceVersion.RELEASE_8;
              }

              @Override
              public boolean process(
                  Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
                if (roundEnv.processingOver()) {
                  try {
                    r.run();
                  } catch (Exception e) {
                    throw new AssertionError(e);
                  }
                }
                return false;
              }
            }));

    elements = testCompiler.getElements();
    testCompiler.enter();
  }

  private Path getSourceFile(String filename) {
    return TestDataHelper.getTestDataScenario(this, "descriptor_and_signature_factories")
        .resolve(filename);
  }

  protected List<String> getTestErrors(
      Function<FieldNode, String> fieldNodeExpectedValueGetter,
      Function<MethodNode, String> methodNodeExpectedValueGetter,
      Function<ClassNode, String> classNodeExpectedValueGetter,
      Function<Element, String> elementActualValueGetter)
      throws IOException {
    TypeElement fooElement = elements.getTypeElement("com.facebook.foo.Foo");
    findErrors(
        fooElement,
        fieldNodeExpectedValueGetter,
        methodNodeExpectedValueGetter,
        classNodeExpectedValueGetter,
        elementActualValueGetter);
    return errors;
  }

  private void findErrors(
      TypeElement typeElement,
      Function<FieldNode, String> fieldNodeExpectedValueGetter,
      Function<MethodNode, String> methodNodeExpectedValueGetter,
      Function<ClassNode, String> classNodeExpectedValueGetter,
      Function<Element, String> elementActualValueGetter)
      throws IOException {
    ClassNode typeNode = getClassNode(elements.getBinaryName(typeElement).toString());
    for (Element enclosedElement : typeElement.getEnclosedElements()) {
      Name elementName = enclosedElement.getSimpleName();
      String actual = elementActualValueGetter.apply(enclosedElement);
      switch (enclosedElement.getKind()) {
        case FIELD:
          checkValue(
              "Field",
              elementName,
              fieldNodeExpectedValueGetter.apply(getFieldNode(typeNode, elementName)),
              actual);
          break;
        case CONSTRUCTOR:
        case METHOD:
          checkValue(
              "Method",
              elementName,
              methodNodeExpectedValueGetter.apply(getMethodNode(typeNode, elementName)),
              actual);
          break;
        case ANNOTATION_TYPE:
        case CLASS:
        case ENUM:
        case INTERFACE:
          ClassNode innerTypeNode =
              getClassNode(elements.getBinaryName((TypeElement) enclosedElement).toString());
          checkValue(
              "Class", elementName, classNodeExpectedValueGetter.apply(innerTypeNode), actual);

          findErrors(
              (TypeElement) enclosedElement,
              fieldNodeExpectedValueGetter,
              methodNodeExpectedValueGetter,
              classNodeExpectedValueGetter,
              elementActualValueGetter);
          break;
          // $CASES-OMITTED$
        default:
          fail(
              String.format(
                  "Didn't implement testing for element kind %s", enclosedElement.getKind()));
          continue;
      }
    }
  }

  private void checkValue(String type, Name elementName, String expected, String actual) {
    if (expected != actual && (expected == null || !expected.equals(actual))) {
      errors.add(
          String.format(
              "%s %s:\n\tExpected: %s\n\tActual: %s", type, elementName, expected, actual));
    }
  }

  private FieldNode getFieldNode(ClassNode classNode, Name name) {
    return classNode
        .fields
        .stream()
        .filter(field -> name.contentEquals(field.name))
        .findFirst()
        .orElse(null);
  }

  private MethodNode getMethodNode(ClassNode classNode, Name name) {
    return classNode
        .methods
        .stream()
        .filter(field -> name.contentEquals(field.name))
        .findFirst()
        .orElse(null);
  }

  private ClassNode getClassNode(String classBinaryName) throws IOException {
    ClassNode classNode = new ClassNode(Opcodes.ASM7);
    correctClasses.acceptClassVisitor(classBinaryName, 0, classNode);
    return classNode;
  }

  public interface TestRunnable {
    void run() throws Exception;
  }
}
