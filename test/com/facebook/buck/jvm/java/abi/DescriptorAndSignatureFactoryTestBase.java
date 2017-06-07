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

import com.facebook.buck.jvm.java.testutil.compiler.TestCompiler;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import org.junit.Before;
import org.junit.Rule;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

public class DescriptorAndSignatureFactoryTestBase {

  @Rule public TestCompiler testCompiler = new TestCompiler();
  protected Elements elements;
  List<String> errors = new ArrayList<>();

  @Before
  public void setUp() throws IOException {
    Path sourceFile =
        TestDataHelper.getTestDataScenario(this, "descriptor_and_signature_factories")
            .resolve("Foo.java");

    testCompiler.addSourceFile(sourceFile);

    elements = testCompiler.getElements();

    testCompiler.compile();
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
    ClassNode classNode = new ClassNode(Opcodes.ASM5);
    testCompiler.getClasses().acceptClassVisitor(classBinaryName, 0, classNode);
    return classNode;
  }
}
