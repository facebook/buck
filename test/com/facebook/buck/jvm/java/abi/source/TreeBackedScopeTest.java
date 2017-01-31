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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.jvm.java.testutil.CompilerTreeApiParameterized;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.sun.source.tree.Scope;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

@RunWith(CompilerTreeApiParameterized.class)
public class TreeBackedScopeTest extends CompilerTreeApiParameterizedTest {
  @Test
  public void testClassScopeContainsTypeParameters() throws IOException {
    // According to the docs for Scope, Scopes do not contain the members of a class; for those
    // you must examine the associated TypeElement
    compile(Joiner.on('\n').join(
        "abstract class Foo<T, U> {",
        "  int field;",
        "  abstract void method();",
        "  class InnerClass { }",
        "}"));

    TypeElement fooElement = elements.getTypeElement("Foo");
    Scope classScope = trees.getScope(trees.getPath(fooElement));

    assertSame(fooElement, classScope.getEnclosingClass());
    assertNull(classScope.getEnclosingMethod());

    if (testingJavac()) {
      assertScopeContentsAtLeast(classScope, fooElement.getTypeParameters());

      // class scope also contains vars for this and super
      assertSame(4, Iterables.size(classScope.getLocalElements()));
    } else {
      // In our implementation we don't bother with this and super
      // TODO(jkeljo): Do we need to for AP support?
      assertScopeContentsExactly(classScope, fooElement.getTypeParameters());
    }
  }

  @Test
  public void testInnerClassScopeNestsWithinClassScope() throws IOException {
    compile(Joiner.on('\n').join(
        "class Foo<T,U> {",
        "  class Bar<V> { }",
        "}"
    ));

    TypeElement barElement = elements.getTypeElement("Foo.Bar");
    Scope barScope = trees.getScope(trees.getPath(barElement));
    assertSame(barElement, barScope.getEnclosingClass());
    assertNull(barScope.getEnclosingMethod());
    assertScopeContentsAtLeast(barScope, barElement.getTypeParameters());

    TypeElement fooElement = elements.getTypeElement("Foo");
    Scope fooScope = trees.getScope(trees.getPath(fooElement));
    Scope parentScope = barScope.getEnclosingScope();
    // fooScope and parentScope are conceptually the same thing, but in the javac implementation
    // you actually get different objects. Because we can't test that they're the same instance,
    // we at least test that they behave the same.
    assertEquals(
        Lists.newArrayList(fooScope.getLocalElements()),
        Lists.newArrayList(parentScope.getLocalElements()));
  }

  @Test
  public void testFileScopeContainsAllImportsAndAllTopLevelClassesInTheFile() throws IOException {
    compile(Joiner.on('\n').join(
        "import java.util.List;",
        "import java.io.InputStream;",
        "abstract class Foo implements List<InputStream> { }",
        "class Bar { }"
    ));

    TypeElement fooElement = elements.getTypeElement("Foo");
    Scope classScope = trees.getScope(trees.getPath(fooElement));
    Scope fileScope = classScope.getEnclosingScope();

    assertNull(fileScope.getEnclosingClass());
    assertNull(fileScope.getEnclosingMethod());

    assertScopeContentsExactly(fileScope, "java.util.List", "java.io.InputStream", "Foo", "Bar");
  }

  private void assertScopeContentsExactly(Scope scope, Collection<? extends Element> elements) {
    assertScopeContentsExactly(scope, elements.toArray(new Element[elements.size()]));
  }

  private void assertScopeContentsExactly(Scope scope, String... contents) {
    List<TypeElement> expected = RichStream.from(Arrays.stream(contents))
        .map(name -> elements.getTypeElement(name))
        .toImmutableList();

    assertScopeContentsExactly(scope, expected);
  }

  private void assertScopeContentsExactly(Scope scope, Element... expected) {
    Set<String> actual = new HashSet<>();
    for (Element element : scope.getLocalElements()) {
      actual.add(element.toString());
    }

    Set<String> expectedSet =
        new HashSet<>(
            Arrays.stream(expected)
              .map(entry -> entry.toString())
              .collect(Collectors.toList()));

    assertEquals(expectedSet, actual);
  }

  private void assertScopeContentsAtLeast(Scope scope, Collection<? extends Element> elements) {
    assertScopeContentsAtLeast(scope, elements.toArray(new Element[elements.size()]));
  }

  private void assertScopeContentsAtLeast(Scope scope, Element... expected) {
    Set<String> actual = new HashSet<>();
    for (Element element : scope.getLocalElements()) {
      actual.add(element.toString());
    }

    for (Element element : expected) {
      assertTrue(
          String.format("Missing element: %s", element.getSimpleName()),
          actual.contains(element.toString()));
    }
  }
}
