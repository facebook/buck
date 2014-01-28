/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.rules.coercer.Pair;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class TypeCoercerTest {
  private final TypeCoercerFactory typeCoercerFactory = new TypeCoercerFactory();
  private final BuildRuleResolver buildRuleResolver = new BuildRuleResolver();

  @Test
  public void coercingStringMapOfIntListsShouldBeIdentity()
      throws CoerceFailedException, NoSuchFieldException {
    Type type = TestFields.class.getField("stringMapOfLists").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    ImmutableMap<String, ImmutableList<Integer>> input =
        ImmutableMap.of(
            "foo", ImmutableList.of(4, 5),
            "bar", ImmutableList.of(6, 7));
    Object result = coercer.coerce(buildRuleResolver, Paths.get(""), input);
    assertEquals(input, result);
  }

  @Test
  public void coercingNestedListOfSetsShouldActuallyCreateSets()
      throws CoerceFailedException, NoSuchFieldException {
    Type type = TestFields.class.getField("listOfSets").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    ImmutableList<ImmutableList<Integer>> input =
        ImmutableList.of(
            ImmutableList.of(4, 4, 5),
            ImmutableList.of(6, 7));
    Object result = coercer.coerce(buildRuleResolver, Paths.get(""), input);
    ImmutableList<ImmutableSet<Integer>> expectedResult =
        ImmutableList.of(
            ImmutableSet.of(4, 5),
            ImmutableSet.of(6, 7));
    assertEquals(expectedResult, result);
  }

  @Test
  public void coercingSortedSetsShouldActuallyCreateSortedSets()
      throws CoerceFailedException, NoSuchFieldException {
    Type type = TestFields.class.getField("sortedSetOfStrings").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    ImmutableList<String> input = ImmutableList.of("a", "c", "b", "a");
    Object result = coercer.coerce(buildRuleResolver, Paths.get(""), input);
    ImmutableSortedSet<String> expectedResult = ImmutableSortedSet.copyOf(input);
    assertEquals(expectedResult, result);
  }

  @Test
  public void shouldAllowListTypeToBeSuperclassOfResult()
      throws CoerceFailedException, NoSuchFieldException {
    Type type = TestFields.class.getField("superclassOfImmutableList").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    ImmutableList<String> input = ImmutableList.of("a", "b", "c");
    Object result = coercer.coerce(buildRuleResolver, Paths.get(""), input);
    assertEquals(ImmutableList.of("a", "b", "c"), result);
  }

  @Test
  public void shouldAllowMapTypeToBeSuperclassOfResult()
      throws CoerceFailedException, NoSuchFieldException {
    Type type = TestFields.class.getField("superclassOfImmutableMap").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    ImmutableMap<String, String> input = ImmutableMap.of("a", "b");
    Object result = coercer.coerce(buildRuleResolver, Paths.get(""), input);
    assertEquals(input, result);
  }

  /**
   * Cannot declare a field that is not a superclass (i.e. LinkedList instead of List or
   * ImmutableList). Required as TypeCoercer will assign an ImmutableList.
   */
  @Test(expected = IllegalArgumentException.class)
  public void disallowSubclassOfSuperclass() throws NoSuchFieldException {
    Type type = TestFields.class.getField("subclassOfList").getGenericType();
    typeCoercerFactory.typeCoercerForType(type);
  }

  /**
   * Specifying a field type that matches too many coercers should be disallowed.
   */
  @Test(expected = IllegalArgumentException.class)
  public void disallowAmbiguousSimpleTypes() throws NoSuchFieldException {
    Type type = TestFields.class.getField("object").getGenericType();
    typeCoercerFactory.typeCoercerForType(type);
  }

  @Test(expected = IllegalArgumentException.class)
  public void disallowMapWithOptionalKeys() throws NoSuchFieldException {
    Type type = TestFields.class.getField("optionalIntegerMapOfStrings").getGenericType();
    typeCoercerFactory.typeCoercerForType(type);
  }

  /**
   * Traverse visits every element of an input value without coercing to the output type.
   */
  @Test
  public void traverseShouldVisitEveryObject() throws NoSuchFieldException {
    Type type = TestFields.class.getField("stringMapOfLists").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    final ImmutableMap<String, ImmutableList<String>> input =
        ImmutableMap.of(
            "foo", ImmutableList.of("//foo:bar", "//foo:baz"),
            "bar", ImmutableList.of(":bar", "//foo:foo"));

    TestTraversal traversal = new TestTraversal();
    coercer.traverse(input, traversal);
    List<Object> objects = traversal.getObjects();

    assertThat(objects, Matchers.<Object>contains(ImmutableList.of(
        sameInstance((Object)input),
        is((Object)"foo"),
        sameInstance((Object)input.get("foo")),
        is((Object)"//foo:bar"),
        is((Object)"//foo:baz"),
        is((Object)"bar"),
        sameInstance((Object)input.get("bar")),
        is((Object)":bar"),
        is((Object)"//foo:foo"))));
  }

  @Test
  public void hasElementTypesForContainers() throws NoSuchFieldException {
    Type type = TestFields.class.getField("stringMapOfLists").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    assertTrue(coercer.hasElementClass(String.class));
    assertTrue(coercer.hasElementClass(Integer.class));
    assertTrue(coercer.hasElementClass(Integer.class, String.class));
    assertTrue(coercer.hasElementClass(Integer.class, SourcePath.class));
    assertFalse(coercer.hasElementClass(SourcePath.class));
  }

  @Test
  public void hasElementTypesForPrimitives() throws NoSuchFieldException {
    Type type = TestFields.class.getField("primitiveString").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    assertTrue(coercer.hasElementClass(String.class));
    assertFalse(coercer.hasElementClass(Integer.class));
  }

  @Test
  public void coerceToEitherLeftOrRight() throws NoSuchFieldException, CoerceFailedException {
    Type type = TestFields.class.getField("eitherStringOrStringList").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    String inputString = "a_string";
    ImmutableList<String> inputList = ImmutableList.of("a", "b");

    assertEquals(
        Either.ofLeft(inputString),
        coercer.coerce(buildRuleResolver, Paths.get(""), inputString));
    assertEquals(
        Either.ofRight(inputList),
        coercer.coerce(buildRuleResolver, Paths.get(""), inputList));
  }

  @Test
  public void traverseWithEitherAndContainer() throws NoSuchFieldException {
    Type type = TestFields.class.getField("eitherStringOrStringList").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    TestTraversal traversal = new TestTraversal();
    ImmutableList<String> input = ImmutableList.of("foo");
    coercer.traverse(input, traversal);
    assertThat(
        traversal.getObjects(),
        Matchers.<Object>contains(ImmutableList.of(
            sameInstance((Object)input),
            sameInstance((Object)input.get(0)))));

    traversal = new TestTraversal();
    String input2 = "foo";
    coercer.traverse(input2, traversal);
    assertThat(traversal.getObjects(), hasSize(1));
    assertThat(traversal.getObjects().get(0), sameInstance((Object) "foo"));
  }

  static class TestTraversal implements ParamInfo.Traversal {
    private List<Object> objects = Lists.newArrayList();

    public List<Object> getObjects() {
      return objects;
    }

    @Override
    public void traverse(Object object) {
      objects.add(object);
    }
  }

  @Test
  public void pairTypeCoercerCanCoerceFromTwoElementLists()
      throws NoSuchFieldException, CoerceFailedException {
    Type type = TestFields.class.getField("pairOfPathsAndStrings").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    ImmutableList<?> input = ImmutableList.of("foo.m", "-foo -bar");
    assertEquals(
        new Pair<>(Paths.get("foo.m"), "-foo -bar"),
        coercer.coerce(buildRuleResolver, Paths.get(""), input));
  }

  @Test
  public void hasElementTypesForPair() throws NoSuchFieldException {
    Type type = TestFields.class.getField("pairOfPathsAndStrings").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    assertTrue(coercer.hasElementClass(String.class));
    assertTrue(coercer.hasElementClass(Path.class));
    assertFalse(coercer.hasElementClass(Integer.class));
  }

  @Test
  public void traverseWithPair() throws NoSuchFieldException {
    Type type = TestFields.class.getField("pairOfPathsAndStrings").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    TestTraversal traversal = new TestTraversal();
    ImmutableList<?> input = ImmutableList.of("foo", "bar");
    coercer.traverse(input, traversal);
    assertThat(
        traversal.getObjects(),
        Matchers.<Object>contains(ImmutableList.of(
            sameInstance(input.get(0)),
            sameInstance(input.get(1)))));
  }

  @SuppressWarnings("unused")
  static class TestFields {
    public ImmutableMap<String, ImmutableList<Integer>> stringMapOfLists;
    public ImmutableList<ImmutableSet<Integer>> listOfSets;
    public ImmutableSet<SourcePath> setOfSourcePaths;
    public ImmutableSortedSet<String> sortedSetOfStrings;
    public List<String> superclassOfImmutableList;
    public Map<String, String> superclassOfImmutableMap;
    public LinkedList<Integer> subclassOfList;
    public Object object;
    public ImmutableMap<String, ImmutableList<BuildTarget>> stringMapOfListOfBuildTargets;
    public Map<Optional<Integer>, String> optionalIntegerMapOfStrings;
    public String primitiveString;
    public Either<String, List<String>> eitherStringOrStringList;
    public Pair<Path, String> pairOfPathsAndStrings;
  }
}
