/*
 * Copyright 2018-present Facebook, Inc.
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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTest;
import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTestRunner;
import com.google.common.base.Joiner;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import javax.lang.model.element.TypeElement;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiTestRunner.class)
public class ElementTreeFinderTest extends CompilerTreeApiTest {
  private static String SOURCE_CODE =
      Joiner.on('\n')
          .join(
              "class AClass<ClassTypeParam> {",
              "  public <MethodTypeParam> void method(int methodParameter) {",
              "  }",
              "  private int field;",
              "}");

  private CompilationUnitTree compilationUnit;
  private ElementTreeFinder finder;

  @Before
  public void setUp() throws IOException {
    compile(SOURCE_CODE);
    compilationUnit = getCompilationUnit();
    finder = ElementTreeFinder.forCompilationUnit(compilationUnit, trees);
  }

  @Test
  public void testFindsTreeForClass() {
    assertThat(finder.getTree(elements.getTypeElement("AClass")), is(findTreeNamed("AClass")));
  }

  @Test
  public void testFindsTreeForClassTypeParameter() {
    assertThat(
        finder.getTree(findTypeParameter("ClassTypeParam", elements.getTypeElement("AClass"))),
        is(findTreeNamed("ClassTypeParam")));
  }

  @Test
  public void testFindsTreeForMethod() {
    assertThat(
        finder.getTree(findMethod("method", elements.getTypeElement("AClass"))),
        is(findTreeNamed("method")));
  }

  @Test
  public void testFindsTreeForMethodParameter() {
    assertThat(
        finder.getTree(
            findParameter(
                "methodParameter", findMethod("method", elements.getTypeElement("AClass")))),
        is(findTreeNamed("methodParameter")));
  }

  @Test
  public void testFindsTreeForMethodTypeParameter() {
    assertThat(
        finder.getTree(
            findTypeParameter(
                "MethodTypeParam", findMethod("method", elements.getTypeElement("AClass")))),
        is(findTreeNamed("MethodTypeParam")));
  }

  @Test
  public void testFindsTreeForField() {
    assertThat(
        finder.getTree(findField("field", elements.getTypeElement("AClass"))),
        is(findTreeNamed("field")));
  }

  private Tree findTreeNamed(CharSequence name) {
    return new TreeScanner<Tree, Void>() {
      @Override
      public Tree visitClass(ClassTree node, Void aVoid) {
        if (node.getSimpleName().contentEquals(name)) {
          return node;
        }

        return super.visitClass(node, aVoid);
      }

      @Override
      public Tree visitMethod(MethodTree node, Void aVoid) {
        if (node.getName().contentEquals(name)) {
          return node;
        }

        return super.visitMethod(node, aVoid);
      }

      @Override
      public Tree visitVariable(VariableTree node, Void aVoid) {
        if (node.getName().contentEquals(name)) {
          return node;
        }

        return null;
      }

      @Override
      public Tree visitTypeParameter(TypeParameterTree node, Void aVoid) {
        if (node.getName().contentEquals(name)) {
          return node;
        }

        return null;
      }

      @Override
      public Tree visitBlock(BlockTree node, Void aVoid) {
        return null;
      }

      @Override
      public Tree reduce(Tree r1, Tree r2) {
        if (r1 == r2) {
          return r1;
        }
        if (r1 != null && r2 != null) {
          throw new AssertionError();
        } else if (r1 != null) {
          return r1;
        } else {
          return r2;
        }
      }
    }.scan(compilationUnit, null);
  }

  private CompilationUnitTree getCompilationUnit() {
    TypeElement foo = elements.getTypeElement("AClass");
    return trees.getPath(foo).getCompilationUnit();
  }
}
