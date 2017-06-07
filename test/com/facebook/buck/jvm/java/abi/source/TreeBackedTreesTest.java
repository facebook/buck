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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiParameterized;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import java.io.IOException;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiParameterized.class)
public class TreeBackedTreesTest extends CompilerTreeApiParameterizedTest {
  @Test
  public void testGetTreeGetPathRoundtripTypeElement() throws IOException {
    compile("class Foo<T, U> { }");

    TypeElement fooElement = elements.getTypeElement("Foo");

    Tree fooTree = trees.getTree(fooElement);
    TreePath fooPath = trees.getPath(fooElement);

    assertSame(fooPath.getLeaf(), fooTree);
    assertSame(fooElement, trees.getElement(fooPath));
  }

  @Test
  public void testGetTreeNullGetPathRoundtripTypeParameterElement() throws IOException {
    compile("class Foo<T, U> { }");

    TypeParameterElement tElement = elements.getTypeElement("Foo").getTypeParameters().get(0);

    Tree tTree = trees.getTree(tElement);
    TreePath tPath = trees.getPath(tElement);

    assertNull(tTree); // Odd behavior by javac, but we'll match it
    assertSame(tElement, trees.getElement(tPath));
  }

  @Test
  public void testNoTreeOrPathForPrecompiledCode() throws IOException {
    compile("class Foo { }");

    TypeElement stringElement = elements.getTypeElement("java.lang.String");

    assertNull(trees.getTree(stringElement));
    assertNull(trees.getPath(stringElement));
  }
}
