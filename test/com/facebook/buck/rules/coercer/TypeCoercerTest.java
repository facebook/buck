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

package com.facebook.buck.rules.coercer;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.syntax.SkylarkNestedSet;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.immutables.value.Value;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class TypeCoercerTest {
  private final TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
  private final FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final CellPathResolver cellRoots = TestCellPathResolver.get(filesystem);

  @Rule public ExpectedException exception = ExpectedException.none();

  @Test
  public void coercingStringMapOfIntListsShouldBeIdentity()
      throws CoerceFailedException, NoSuchFieldException {
    Type type = TestFields.class.getField("stringMapOfLists").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    ImmutableMap<String, ImmutableList<Integer>> input =
        ImmutableMap.of(
            "foo", ImmutableList.of(4, 5),
            "bar", ImmutableList.of(6, 7));
    Object result = coercer.coerce(cellRoots, filesystem, Paths.get(""), input);
    assertEquals(input, result);
  }

  @Test
  public void coercingNestedListOfSetsShouldActuallyCreateSets()
      throws CoerceFailedException, NoSuchFieldException {
    Type type = TestFields.class.getField("listOfSets").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    ImmutableList<ImmutableList<Integer>> input =
        ImmutableList.of(ImmutableList.of(4, 4, 5), ImmutableList.of(6, 7));
    Object result = coercer.coerce(cellRoots, filesystem, Paths.get(""), input);
    ImmutableList<ImmutableSet<Integer>> expectedResult =
        ImmutableList.of(ImmutableSet.of(4, 5), ImmutableSet.of(6, 7));
    assertEquals(expectedResult, result);
  }

  @Test
  public void coercingSortedSetsShouldThrowOnDuplicates() throws NoSuchFieldException {
    Type type = TestFields.class.getField("sortedSetOfStrings").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    ImmutableList<String> input = ImmutableList.of("a", "a");
    try {
      coercer.coerce(cellRoots, filesystem, Paths.get(""), input);
      fail();
    } catch (CoerceFailedException e) {
      assertEquals("duplicate element \"a\"", e.getMessage());
    }
  }

  @Test
  public void coercingSortedSetsShouldActuallyCreateSortedSets()
      throws CoerceFailedException, NoSuchFieldException {
    Type type = TestFields.class.getField("sortedSetOfStrings").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    ImmutableList<String> input = ImmutableList.of("c", "a", "d", "b");
    Object result = coercer.coerce(cellRoots, filesystem, Paths.get(""), input);
    ImmutableSortedSet<String> expectedResult = ImmutableSortedSet.copyOf(input);
    assertEquals(expectedResult, result);
  }

  @Test
  public void shouldAllowListTypeToBeSuperclassOfResult()
      throws CoerceFailedException, NoSuchFieldException {
    Type type = TestFields.class.getField("superclassOfImmutableList").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    ImmutableList<String> input = ImmutableList.of("a", "b", "c");
    Object result = coercer.coerce(cellRoots, filesystem, Paths.get(""), input);
    assertEquals(ImmutableList.of("a", "b", "c"), result);
  }

  @Test
  public void shouldAllowMapTypeToBeSuperclassOfResult()
      throws CoerceFailedException, NoSuchFieldException {
    Type type = TestFields.class.getField("superclassOfImmutableMap").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    ImmutableMap<String, String> input = ImmutableMap.of("a", "b");
    Object result = coercer.coerce(cellRoots, filesystem, Paths.get(""), input);
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

  /** Specifying a field type that matches too many coercers should be disallowed. */
  @Test(expected = IllegalArgumentException.class)
  public void disallowAmbiguousSimpleTypes() throws NoSuchFieldException {
    Type type = TestFields.class.getField("object").getGenericType();
    typeCoercerFactory.typeCoercerForType(type);
  }

  /** Traverse visits every element of an input value without coercing to the output type. */
  @Test
  public void traverseShouldVisitEveryObject() throws NoSuchFieldException {
    Type type = TestFields.class.getField("stringMapOfLists").getGenericType();
    @SuppressWarnings("unchecked")
    TypeCoercer<ImmutableMap<String, ImmutableList<String>>> coercer =
        (TypeCoercer<ImmutableMap<String, ImmutableList<String>>>)
            typeCoercerFactory.typeCoercerForType(type);

    ImmutableMap<String, ImmutableList<String>> input =
        ImmutableMap.of(
            "foo", ImmutableList.of("//foo:bar", "//foo:baz"),
            "bar", ImmutableList.of(":bar", "//foo:foo"));

    TestTraversal traversal = new TestTraversal();
    coercer.traverse(cellRoots, input, traversal);

    Matcher<Iterable<?>> matcher =
        Matchers.contains(
            ImmutableList.of(
                sameInstance((Object) input),
                is((Object) "foo"),
                sameInstance((Object) input.get("foo")),
                is((Object) "//foo:bar"),
                is((Object) "//foo:baz"),
                is((Object) "bar"),
                sameInstance((Object) input.get("bar")),
                is((Object) ":bar"),
                is((Object) "//foo:foo")));
    assertThat(traversal.getObjects(), matcher);
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
    Type type = TestFields.class.getField("eitherStringSetOrStringToStringMap").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    Set<String> inputSet = ImmutableSet.of("a", "b", "x");
    Map<String, String> inputMap =
        ImmutableMap.of(
            "key1", "One",
            "key2", "Two");

    assertEquals(
        Either.ofLeft(inputSet), coercer.coerce(cellRoots, filesystem, Paths.get(""), inputSet));
    assertEquals(
        Either.ofRight(inputMap), coercer.coerce(cellRoots, filesystem, Paths.get(""), inputMap));
  }

  @Test
  public void coercedEitherThrowsOnAccessingMissingLeft()
      throws NoSuchFieldException, CoerceFailedException {
    Type type = TestFields.class.getField("eitherStringSetOrStringToStringMap").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    Map<String, String> inputMap =
        ImmutableMap.of(
            "key1", "One",
            "key2", "Two");
    Either<?, ?> either =
        (Either<?, ?>) coercer.coerce(cellRoots, filesystem, Paths.get(""), inputMap);
    assertEquals(inputMap, either.getRight());
    exception.expect(RuntimeException.class);
    either.getLeft();
  }

  @Test
  public void coercedEitherThrowsOnAccessingMissingRight()
      throws NoSuchFieldException, CoerceFailedException {
    Type type = TestFields.class.getField("eitherStringSetOrStringToStringMap").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    Set<String> inputSet = ImmutableSet.of("a", "b", "x");
    Either<?, ?> either =
        (Either<?, ?>) coercer.coerce(cellRoots, filesystem, Paths.get(""), inputSet);
    assertEquals(inputSet, either.getLeft());
    exception.expect(RuntimeException.class);
    either.getRight();
  }

  @Test
  public void coerceToEitherLeftOrRightWithCollections()
      throws NoSuchFieldException, CoerceFailedException {
    Type type = TestFields.class.getField("eitherStringOrStringList").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    String inputString = "a_string";
    ImmutableList<String> inputList = ImmutableList.of("a", "b");

    assertEquals(
        Either.ofLeft(inputString),
        coercer.coerce(cellRoots, filesystem, Paths.get(""), inputString));
    assertEquals(
        Either.ofRight(inputList), coercer.coerce(cellRoots, filesystem, Paths.get(""), inputList));
  }

  @Test
  public void traverseWithEitherAndContainer() throws NoSuchFieldException {
    Type type = TestFields.class.getField("eitherStringOrStringList").getGenericType();
    @SuppressWarnings("unchecked")
    TypeCoercer<Either<String, List<String>>> coercer =
        (TypeCoercer<Either<String, List<String>>>) typeCoercerFactory.typeCoercerForType(type);

    TestTraversal traversal = new TestTraversal();
    Either<String, List<String>> input = Either.ofRight(ImmutableList.of("foo"));
    coercer.traverse(cellRoots, input, traversal);
    assertThat(
        traversal.getObjects(),
        Matchers.contains(
            ImmutableList.of(
                sameInstance((Object) input.getRight()),
                sameInstance((Object) input.getRight().get(0)))));

    traversal = new TestTraversal();
    Either<String, List<String>> input2 = Either.ofLeft("foo");
    coercer.traverse(cellRoots, input2, traversal);
    assertThat(traversal.getObjects(), hasSize(1));
    assertThat(traversal.getObjects().get(0), sameInstance("foo"));
  }

  static class TestTraversal implements TypeCoercer.Traversal {
    private List<Object> objects = new ArrayList<>();

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
        coercer.coerce(cellRoots, filesystem, Paths.get(""), input));
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
    @SuppressWarnings("unchecked")
    TypeCoercer<Pair<Path, String>> coercer =
        (TypeCoercer<Pair<Path, String>>) typeCoercerFactory.typeCoercerForType(type);

    TestTraversal traversal = new TestTraversal();
    Pair<Path, String> input = new Pair<>(Paths.get("foo"), "bar");
    coercer.traverse(cellRoots, input, traversal);
    assertThat(
        traversal.getObjects(),
        Matchers.contains(
            ImmutableList.of(
                sameInstance((Object) input.getFirst()),
                sameInstance((Object) input.getSecond()))));
  }

  @Test
  public void coercingAppleSourcePaths() throws NoSuchFieldException, CoerceFailedException {
    Type type = TestFields.class.getField("listOfSourcesWithFlags").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    ImmutableList<String> input = ImmutableList.of("foo.m", "bar.m");
    Object result = coercer.coerce(cellRoots, filesystem, Paths.get(""), input);
    ImmutableList<SourceWithFlags> expectedResult =
        ImmutableList.of(
            SourceWithFlags.of(FakeSourcePath.of("foo.m")),
            SourceWithFlags.of(FakeSourcePath.of("bar.m")));
    assertEquals(expectedResult, result);
  }

  @Test
  public void coercingAppleSourcePathsWithFlags()
      throws NoSuchFieldException, CoerceFailedException {
    Type type = TestFields.class.getField("listOfSourcesWithFlags").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    ImmutableList<?> input =
        ImmutableList.of(
            ImmutableList.of("foo.m", ImmutableList.of("-Wall", "-Werror")),
            ImmutableList.of("bar.m", ImmutableList.of("-fobjc-arc")));
    Object result = coercer.coerce(cellRoots, filesystem, Paths.get(""), input);
    ImmutableList<SourceWithFlags> expectedResult =
        ImmutableList.of(
            SourceWithFlags.of(FakeSourcePath.of("foo.m"), ImmutableList.of("-Wall", "-Werror")),
            SourceWithFlags.of(FakeSourcePath.of("bar.m"), ImmutableList.of("-fobjc-arc")));
    assertEquals(expectedResult, result);
  }

  @Test
  public void coercingHeterogeneousAppleSourceGroups()
      throws NoSuchFieldException, CoerceFailedException {
    Type type = TestFields.class.getField("listOfSourcesWithFlags").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    ImmutableList<?> input =
        ImmutableList.of(
            "Group1/foo.m",
            ImmutableList.of("Group1/bar.m", ImmutableList.of("-Wall", "-Werror")),
            "Group2/baz.m",
            ImmutableList.of("Group2/blech.m", ImmutableList.of("-fobjc-arc")));
    Object result = coercer.coerce(cellRoots, filesystem, Paths.get(""), input);
    ImmutableList<SourceWithFlags> expectedResult =
        ImmutableList.of(
            SourceWithFlags.of(FakeSourcePath.of("Group1/foo.m")),
            SourceWithFlags.of(
                FakeSourcePath.of("Group1/bar.m"), ImmutableList.of("-Wall", "-Werror")),
            SourceWithFlags.of(FakeSourcePath.of("Group2/baz.m")),
            SourceWithFlags.of(
                FakeSourcePath.of("Group2/blech.m"), ImmutableList.of("-fobjc-arc")));
    assertEquals(expectedResult, result);
  }

  @Test
  public void coerceToNeededCoverageSpec() throws NoSuchFieldException, CoerceFailedException {
    Type type = TestFields.class.getField("listOfNeededCoverageSpecs").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    ImmutableList<?> input =
        ImmutableList.of(
            ImmutableList.of(0, "//some:build-target"),
            ImmutableList.of(90, "//other/build:target"),
            ImmutableList.of(100, "//:target", "some/path.py"));
    Object result = coercer.coerce(cellRoots, filesystem, Paths.get(""), input);
    ImmutableList<NeededCoverageSpec> expectedResult =
        ImmutableList.of(
            NeededCoverageSpec.of(
                0, BuildTargetFactory.newInstance("//some:build-target"), Optional.empty()),
            NeededCoverageSpec.of(
                90, BuildTargetFactory.newInstance("//other/build:target"), Optional.empty()),
            NeededCoverageSpec.of(
                100, BuildTargetFactory.newInstance("//:target"), Optional.of("some/path.py")));
    assertEquals(expectedResult, result);
  }

  @Test
  public void invalidCoverageResultsInError() throws NoSuchFieldException {
    Type type = TestFields.class.getField("listOfNeededCoverageSpecs").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    try {
      coercer.coerce(
          cellRoots,
          filesystem,
          Paths.get(""),
          ImmutableList.of(ImmutableList.of(-5, "//some:build-target")));
      fail(String.format("%d should not be convertable to a spec", -5));
    } catch (CoerceFailedException e) {
      assertThat(
          e.getMessage(),
          Matchers.endsWith("the needed coverage ratio should be in range [0, 100]"));
    }

    try {
      coercer.coerce(
          cellRoots,
          filesystem,
          Paths.get(""),
          ImmutableList.of(ImmutableList.of(101, "//some:build-target")));
      fail(String.format("%d should not be convertable to a spec", 101));
    } catch (CoerceFailedException e) {
      assertThat(
          e.getMessage(),
          Matchers.endsWith("the needed coverage ratio should be in range [0, 100]"));
    }

    try {
      coercer.coerce(
          cellRoots,
          filesystem,
          Paths.get(""),
          ImmutableList.of(ImmutableList.of(50.5f, "//some:build-target")));
      fail(String.format("%f should not be convertable to a spec", 50.5f));
    } catch (CoerceFailedException e) {
      assertThat(
          e.getMessage(),
          Matchers.endsWith("the needed coverage ratio should be an integral number"));
    }

    try {
      coercer.coerce(
          cellRoots,
          filesystem,
          Paths.get(""),
          ImmutableList.of(ImmutableList.of(0.3f, "//some:build-target")));
      fail(String.format("%f should not be convertable to a spec", 0.3f));
    } catch (CoerceFailedException e) {
      assertThat(
          e.getMessage(),
          Matchers.endsWith("the needed coverage ratio should be an integral number"));
    }
  }

  @Test
  public void coerceToEnumShouldWorkInList() throws NoSuchFieldException, CoerceFailedException {
    Type type = TestFields.class.getField("listOfTestEnums").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);
    ImmutableList<String> input = ImmutableList.of("PURPLE", "RED", "RED", "PURPLE");

    Object result = coercer.coerce(cellRoots, filesystem, Paths.get(""), input);
    ImmutableList<TestEnum> expected =
        ImmutableList.of(TestEnum.PURPLE, TestEnum.RED, TestEnum.RED, TestEnum.PURPLE);

    assertEquals(expected, result);
  }

  @Test
  public void coerceToEnumShouldWorkInSet() throws NoSuchFieldException, CoerceFailedException {
    Type type = TestFields.class.getField("setOfTestEnums").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);
    ImmutableSet<String> input = ImmutableSet.of("PURPLE", "PINK", "RED");

    Object result = coercer.coerce(cellRoots, filesystem, Paths.get(""), input);
    ImmutableSet<TestEnum> expected = ImmutableSet.of(TestEnum.PURPLE, TestEnum.PINK, TestEnum.RED);

    assertEquals(expected, result);
  }

  @Test
  public void coerceToEnumsShouldWorkWithUpperAndLowerCaseValues()
      throws NoSuchFieldException, CoerceFailedException {
    Type type = TestFields.class.getField("listOfTestEnums").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);
    ImmutableList<String> input = ImmutableList.of("grey", "YELLOW", "red", "PURPLE");

    Object result = coercer.coerce(cellRoots, filesystem, Paths.get(""), input);
    ImmutableList<TestEnum> expected =
        ImmutableList.of(TestEnum.grey, TestEnum.yellow, TestEnum.RED, TestEnum.PURPLE);

    assertEquals(expected, result);
  }

  @Test
  public void coerceFromTurkishIsShouldWork() throws NoSuchFieldException, CoerceFailedException {
    Type type = TestFields.class.getField("listOfTestEnums").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);
    String pinkWithLowercaseTurkishI = "p\u0131nk";
    String pinkWithUppercaseTurkishI = "P\u0130NK";
    String whiteWithLowercaseTurkishI = "wh\u0131te";
    String whiteWithUppercaseTurkishI = "WH\u0130TE";

    ImmutableList<String> input =
        ImmutableList.of(
            pinkWithLowercaseTurkishI,
            pinkWithUppercaseTurkishI,
            whiteWithLowercaseTurkishI,
            whiteWithUppercaseTurkishI);
    ImmutableList<TestEnum> expected =
        ImmutableList.of(TestEnum.PINK, TestEnum.PINK, TestEnum.white, TestEnum.white);
    Object result = coercer.coerce(cellRoots, filesystem, Paths.get(""), input);
    assertEquals(expected, result);
  }

  @Test
  public void coerceToTurkishIsShouldWork() throws NoSuchFieldException, CoerceFailedException {
    Type type = TestFields.class.getField("listOfTestEnums").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);
    String violetWithLowerCaseTurkishI = "v\u0131olet";
    String violetWithUpperCaseTurkishI = "V\u0130OLET";
    ImmutableList<String> input =
        ImmutableList.of(
            "violet", "VIOLET", violetWithLowerCaseTurkishI, violetWithUpperCaseTurkishI);
    ImmutableList<TestEnum> expected =
        ImmutableList.of(TestEnum.VIOLET, TestEnum.VIOLET, TestEnum.VIOLET, TestEnum.VIOLET);

    Object result = coercer.coerce(cellRoots, filesystem, Paths.get(""), input);
    assertEquals(expected, result);
  }

  @Test
  public void invalidSourcePathShouldGiveSpecificErrorMsg()
      throws NoSuchFieldException, IOException {
    Type type = TestFields.class.getField("setOfSourcePaths").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);

    Path baratheon = Paths.get("Baratheon.java");
    Path lannister = Paths.get("Lannister.java");
    Path stark = Paths.get("Stark.java");
    Path targaryen = Paths.get("Targaryen.java");

    ImmutableList<Path> input = ImmutableList.of(baratheon, lannister, stark, targaryen);

    for (Path p : input) {
      if (!p.equals(baratheon)) {
        filesystem.touch(p);
      }
    }

    try {
      coercer.coerce(cellRoots, filesystem, Paths.get(""), input);
    } catch (CoerceFailedException e) {
      String result = e.getMessage();
      String expected = "cannot coerce 'Baratheon.java'";
      for (Path p : input) {
        if (!p.equals(baratheon)) {
          assertFalse(result.contains(p.toString()));
        }
      }
      assertTrue(result.contains(expected));
    }
  }

  private CoerceFailedException getCoerceException(Type type, Object object) {
    // First just coerce the raw type and save the coercion exception that gets thrown.
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);
    try {
      coercer.coerce(cellRoots, filesystem, Paths.get(""), object);
      fail("should throw");
      throw new RuntimeException(); // Suppress "missing return statement" errors
    } catch (CoerceFailedException e) {
      return e;
    }
  }

  private void assertSameMessage(Exception e1, Exception e2) {
    assertEquals(e1.getMessage(), e2.getMessage());
  }

  @Test
  public void coerceToContainerTypesShouldNotHideInnerCoerceExceptions()
      throws NoSuchFieldException {

    // A string representation of an invalid path, which throws an error when
    // coercing to a `Path` type.
    String invalidPath = "";
    CoerceFailedException pathCoerceException = getCoerceException(Path.class, invalidPath);

    // Verify that the various collection and map types don't mask inner coercion errors.
    assertSameMessage(
        pathCoerceException,
        getCoerceException(
            TestFields.class.getField("stringMapOfPaths").getGenericType(),
            ImmutableMap.of("test", invalidPath)));
    assertSameMessage(
        pathCoerceException,
        getCoerceException(
            TestFields.class.getField("listOfPaths").getGenericType(),
            ImmutableList.of(invalidPath)));
    assertSameMessage(
        pathCoerceException,
        getCoerceException(
            TestFields.class.getField("sortedSetOfPaths").getGenericType(),
            ImmutableList.of(invalidPath)));
    assertSameMessage(
        pathCoerceException,
        getCoerceException(
            TestFields.class.getField("setOfPaths").getGenericType(),
            ImmutableList.of(invalidPath)));

    // Test that `SourcePath` coercion doesn't change the error from `Path` cercion.
    assertSameMessage(pathCoerceException, getCoerceException(SourcePath.class, invalidPath));

    // Test that regardless of order, we get the same invalid path coercion error
    // when trying to coerce a path-y object.
    assertSameMessage(
        pathCoerceException,
        getCoerceException(
            TestFields.class.getField("eitherListOfStringsOrPath").getGenericType(), invalidPath));
    assertSameMessage(
        pathCoerceException,
        getCoerceException(
            TestFields.class.getField("eitherPathOrListOfStrings").getGenericType(), invalidPath));

    // Test that regardless of order, we get the same invalid list-of-strings
    // coercion error when trying to coerce a list object.
    ImmutableList<Integer> invalidListOfStrings = ImmutableList.of(1, 4, 5);
    CoerceFailedException listOfStringsException =
        getCoerceException(
            TestFields.class.getField("listOfStrings").getGenericType(), invalidListOfStrings);
    assertSameMessage(
        listOfStringsException,
        getCoerceException(
            TestFields.class.getField("eitherListOfStringsOrPath").getGenericType(),
            invalidListOfStrings));
    assertSameMessage(
        listOfStringsException,
        getCoerceException(
            TestFields.class.getField("eitherPathOrListOfStrings").getGenericType(),
            invalidListOfStrings));
  }

  @Test
  public void canCoerceDepsetToImmutableList() throws Exception {
    Type type = TestFields.class.getField("listOfStrings").getGenericType();
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(type);
    SkylarkNestedSet depset =
        SkylarkNestedSet.of(
            String.class, NestedSetBuilder.<String>stableOrder().add("foo").add("bar").build());

    Object result = coercer.coerce(cellRoots, filesystem, Paths.get(""), depset);
    ImmutableList<String> expected = ImmutableList.of("foo", "bar");

    assertEquals(expected, result);
  }

  @Test
  public void canCoerceImmutableType() throws Exception {
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(SomeImmutable.class);
    ImmutableMap<String, Object> map =
        ImmutableMap.of(
            "another_immutable",
            ImmutableMap.of(
                "set_optional", "green",
                "required", "black",
                "default1", "white",
                "default2", "red"));
    Object result = coercer.coerce(cellRoots, filesystem, Paths.get(""), map);

    SomeImmutable expected =
        SomeImmutable.builder()
            .setAnotherImmutable(
                AnotherImmutable.builder()
                    .setInterfaceDefault("blue")
                    .setSetOptional("green")
                    .setRequired("black")
                    .setDefault1("white")
                    .build())
            .build();
    assertEquals(expected, result);
  }

  @Test
  public void cantCoerceImmutableType() throws Exception {
    exception.expectMessage("another_immutable");
    exception.expect(CoerceFailedException.class);
    TypeCoercer<?> coercer = typeCoercerFactory.typeCoercerForType(SomeImmutable.class);
    ImmutableMap<String, Object> map = ImmutableMap.of("wrong_key", ImmutableMap.of());
    coercer.coerce(cellRoots, filesystem, Paths.get(""), map);
  }

  @Test
  public void optionalIntCoercerIsRegistered() {
    assertThat(typeCoercerFactory.typeCoercerForType(OptionalInt.class), notNullValue());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractSomeImmutable {
    AnotherImmutable getAnotherImmutable();
  }

  interface AnotherImmutableInterface {
    Optional<String> getInterfaceOptional();

    @Value.Default
    default String getInterfaceDefault() {
      return "blue";
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractAnotherImmutable implements AnotherImmutableInterface {
    abstract Optional<String> getSetOptional();

    abstract Optional<String> getUnsetOptional();

    abstract String getRequired();

    @Value.Default
    String getDefault1() {
      return "purple";
    }

    @Value.Default
    String getDefault2() {
      return "red";
    }

    @Value.Default
    String getDefault3() {
      return "yellow";
    }
  }

  @SuppressFieldNotInitialized
  static class TestFields {
    public ImmutableMap<String, ImmutableList<Integer>> stringMapOfLists;
    public ImmutableList<ImmutableSet<Integer>> listOfSets;
    public ImmutableSet<SourcePath> setOfSourcePaths;
    public ImmutableSortedSet<String> sortedSetOfStrings;
    public List<String> superclassOfImmutableList;
    public Map<String, String> superclassOfImmutableMap;

    @SuppressWarnings("PMD.LooseCoupling")
    public LinkedList<Integer> subclassOfList;

    public Object object;
    public ImmutableMap<String, ImmutableList<BuildTarget>> stringMapOfListOfBuildTargets;
    public String primitiveString;
    public Either<String, List<String>> eitherStringOrStringList;
    public Either<Set<String>, Map<String, String>> eitherStringSetOrStringToStringMap;
    public Pair<Path, String> pairOfPathsAndStrings;
    public ImmutableList<SourceWithFlags> listOfSourcesWithFlags;
    public ImmutableList<TestEnum> listOfTestEnums;
    public ImmutableMap<String, Path> stringMapOfPaths;
    public ImmutableList<Path> listOfPaths;
    public ImmutableSortedSet<Path> sortedSetOfPaths;
    public ImmutableSet<Path> setOfPaths;
    public ImmutableList<String> listOfStrings;
    public Either<Path, ImmutableList<String>> eitherPathOrListOfStrings;
    public Either<ImmutableList<String>, Path> eitherListOfStringsOrPath;
    public ImmutableSet<TestEnum> setOfTestEnums;
    public ImmutableList<NeededCoverageSpec> listOfNeededCoverageSpecs;
  }

  private enum TestEnum {
    RED,
    PURPLE,
    yellow,
    grey,
    PINK,
    white,
    VIOLET
  }
}
