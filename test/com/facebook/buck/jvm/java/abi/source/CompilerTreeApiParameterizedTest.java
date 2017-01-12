/*
 * Copyright 2016-present Facebook, Inc.
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
import static org.junit.Assert.fail;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;

import org.junit.runners.Parameterized;

import javax.lang.model.element.Name;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

/**
 * A base class for tests that compare the behavior of javac's implementation of Elements and
 * TypeMirrors to Buck's Tree-backed one.
 */
public abstract class CompilerTreeApiParameterizedTest extends CompilerTreeApiTest {
  private static final String JAVAC = "javac";
  private static final String TREES = "trees";

  @Parameterized.Parameter
  public String implementation;

  @Parameterized.Parameters
  public static Object[] getParameters() {
    return new Object[] { JAVAC, TREES };
  }

  @Override
  protected CompilerTreeApiFactory newTreeApiFactory() {
    CompilerTreeApiFactory result = super.newTreeApiFactory();
    if (testingTrees()) {
      result = new TreesCompilerTreeApiFactory(result);
    }

    return result;
  }

  private static class TreesCompilerTreeApiFactory implements CompilerTreeApiFactory {
    private final CompilerTreeApiFactory inner;

    public TreesCompilerTreeApiFactory(CompilerTreeApiFactory inner) {
      this.inner = inner;
    }

    @Override
    public JavacTask newJavacTask(
        JavaCompiler compiler,
        StandardJavaFileManager fileManager,
        Iterable<? extends JavaFileObject> sourceObjects) {
      return new FrontendOnlyJavacTask(
          inner.newJavacTask(compiler, fileManager, sourceObjects));
    }

    @Override
    public Trees getTrees(JavacTask task) {
      return TreeBackedTrees.instance(task);
    }
  }

  protected TypeMirror getTypeParameterUpperBound(String typeName, int typeParameterIndex) {
    TypeParameterElement typeParameter =
        elements.getTypeElement(typeName).getTypeParameters().get(typeParameterIndex);
    TypeVariable typeVariable = (TypeVariable) typeParameter.asType();

    return typeVariable.getUpperBound();
  }

  protected void assertNameEquals(String expected, Name actual) {
    assertEquals(elements.getName(expected), actual);
  }

  protected void assertSameType(TypeMirror expected, TypeMirror actual) {
    if (!types.isSameType(expected, actual)) {
      fail(String.format("Types are not the same.\nExpected: %s\nActual: %s", expected, actual));
    }
  }

  protected void assertNotSameType(TypeMirror expected, TypeMirror actual) {
    if (types.isSameType(expected, actual)) {
      fail(String.format("Expected different types, but both were: %s", expected));
    }
  }

  protected boolean testingJavac() {
    return implementation == JAVAC;
  }

  protected boolean testingTrees() {
    return implementation == TREES;
  }
}
