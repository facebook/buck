/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.starlark.compatible;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.SkylarkInfo;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Objects;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuckSkylarkTypesTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  static class FakeClass<T> {
    private final T value;

    FakeClass(T value) {
      this.value = value;
    }

    T getValue() {
      return value;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof FakeClass)) {
        return false;
      }
      FakeClass<?> fakeClass = (FakeClass<?>) o;
      return Objects.equals(value, fakeClass.value);
    }

    @Override
    public int hashCode() {
      return Objects.hash(value);
    }
  }

  @Test
  public void toJavaListFailsOnWrongType() throws EvalException {
    SkylarkList<?> skylarkList = SkylarkList.Tuple.of(1, 2, 3);

    thrown.expect(EvalException.class);
    BuckSkylarkTypes.toJavaList(skylarkList, FakeClass.class, null);
  }

  @Test
  public void toJavaListCastsGenericsProperly() throws EvalException {
    SkylarkList.Tuple<?> skylarkList =
        SkylarkList.Tuple.<FakeClass<?>>of(
            new FakeClass<>("foo"), new FakeClass<>(1), new FakeClass<>(false));

    ImmutableList<FakeClass<?>> list =
        BuckSkylarkTypes.toJavaList(skylarkList, FakeClass.class, null);
    assertEquals(
        ImmutableList.of(new FakeClass<>("foo"), new FakeClass<>(1), new FakeClass<>(false)), list);
  }

  @Test
  public void toJavaListNonGenericsProperly() throws EvalException {
    SkylarkList<?> skylarkList = SkylarkList.Tuple.of(1, 2, 3);
    ImmutableList<Integer> list = BuckSkylarkTypes.toJavaList(skylarkList, Integer.class, null);

    assertEquals(ImmutableList.of(1, 2, 3), list);
  }

  @Test
  public void asDeepImmutableReturnsPrimitives() {
    Integer integer = 1;
    String string = "some string";

    assertSame(integer, BuckSkylarkTypes.asDeepImmutable(integer));
    assertSame(string, BuckSkylarkTypes.asDeepImmutable(string));
    assertSame(true, BuckSkylarkTypes.asDeepImmutable(true));
  }

  @Test
  public void asDeepImmutableReturnsIdentityForImmutableSkylarkValues() {
    SkylarkList<String> list = SkylarkList.createImmutable(ImmutableList.of("foo", "bar"));
    SkylarkDict<String, String> dict = SkylarkDict.of(null, "foo", "bar");
    SkylarkInfo struct = StructProvider.STRUCT.create(ImmutableMap.of("foo", "bar"), "not found");

    assertTrue(list.isImmutable());
    assertTrue(dict.isImmutable());
    assertTrue(struct.isImmutable());
    assertSame(list, BuckSkylarkTypes.asDeepImmutable(list));
    assertSame(dict, BuckSkylarkTypes.asDeepImmutable(dict));
    assertSame(struct, BuckSkylarkTypes.asDeepImmutable(struct));
  }

  @Test
  public void asDeepImmutableReturnsListOfImmutablesIfSubElementIsMutable() {
    try (TestMutableEnv env = new TestMutableEnv()) {
      SkylarkList.MutableList<String> subList =
          SkylarkList.MutableList.of(env.getEnv(), "foo", "bar");
      SkylarkList.MutableList<SkylarkList.MutableList<String>> list =
          SkylarkList.MutableList.of(env.getEnv(), subList);

      Object result = BuckSkylarkTypes.asDeepImmutable(list);

      assertFalse(list.isImmutable());
      assertTrue(((SkylarkList<?>) result).isImmutable());
      assertEquals(list, result);
      assertNotSame(list, result);
    }
  }

  @Test
  public void asDeepImmutableReturnsListOfImmutablesIfAllElementsAreImmutable() {
    try (TestMutableEnv env = new TestMutableEnv()) {
      SkylarkList.MutableList<String> list = SkylarkList.MutableList.of(env.getEnv(), "foo", "bar");

      Object result = BuckSkylarkTypes.asDeepImmutable(list);

      assertFalse(list.isImmutable());
      assertTrue(((SkylarkList<?>) result).isImmutable());
      assertEquals(list, result);
      assertNotSame(list, result);
    }
  }

  @Test
  public void asDeepImmutableFailsIfListHasMutableElementThatCannotBeMadeImmutable() {
    try (TestMutableEnv env = new TestMutableEnv()) {
      FakeMutableSkylarkObject mutable = new FakeMutableSkylarkObject();
      SkylarkList.MutableList<FakeMutableSkylarkObject> list =
          SkylarkList.MutableList.of(env.getEnv(), mutable);

      thrown.expect(MutableObjectException.class);
      BuckSkylarkTypes.asDeepImmutable(list);
    }
  }

  @Test
  public void asDeepImmutableReturnsDictOfImmutablesIfKeyIsMutable() {
    try (TestMutableEnv env = new TestMutableEnv()) {
      SkylarkList.MutableList<String> list = SkylarkList.MutableList.of(env.getEnv(), "foo", "bar");
      SkylarkDict<SkylarkList.MutableList<String>, String> dict =
          SkylarkDict.of(env.getEnv(), list, "foo");

      Object result = BuckSkylarkTypes.asDeepImmutable(dict);

      assertFalse(dict.isImmutable());
      assertTrue(((SkylarkDict<?, ?>) result).isImmutable());
      assertEquals(dict, result);
      assertNotSame(dict, result);
    }
  }

  @Test
  public void asDeepImmutableReturnsDictOfImmutablesIfValueIsMutable() {
    try (TestMutableEnv env = new TestMutableEnv()) {
      SkylarkList.MutableList<String> list = SkylarkList.MutableList.of(env.getEnv(), "foo", "bar");
      SkylarkDict<String, SkylarkList.MutableList<String>> dict =
          SkylarkDict.of(env.getEnv(), "foo", list);

      Object result = BuckSkylarkTypes.asDeepImmutable(dict);

      assertFalse(dict.isImmutable());
      assertTrue(((SkylarkDict<?, ?>) result).isImmutable());
      assertEquals(dict, result);
      assertNotSame(dict, result);
    }
  }

  @Test
  public void asDeepImmutableReturnsDictOfImmutablesIfAllKeysAndValuesAreImmutable() {
    try (TestMutableEnv env = new TestMutableEnv()) {
      SkylarkDict<String, String> dict = SkylarkDict.of(env.getEnv(), "foo", "bar");

      Object result = BuckSkylarkTypes.asDeepImmutable(dict);

      assertFalse(dict.isImmutable());
      assertTrue(((SkylarkDict<?, ?>) result).isImmutable());
      assertEquals(dict, result);
      assertNotSame(dict, result);
    }
  }

  @Test
  public void asDeepImmutableFailsIfDictHasMutableKeyThatCannotBeMadeImmutable() {
    try (TestMutableEnv env = new TestMutableEnv()) {
      SkylarkDict<FakeMutableSkylarkObject, String> dict =
          SkylarkDict.of(env.getEnv(), new FakeMutableSkylarkObject(), "foo");

      thrown.expect(MutableObjectException.class);
      BuckSkylarkTypes.asDeepImmutable(dict);
    }
  }

  @Test
  public void asDeepImmutableFailsIfDictHasMutableValueThatCannotBeMadeImmutable() {
    try (TestMutableEnv env = new TestMutableEnv()) {
      SkylarkDict<String, FakeMutableSkylarkObject> dict =
          SkylarkDict.of(env.getEnv(), "foo", new FakeMutableSkylarkObject());

      thrown.expect(MutableObjectException.class);
      BuckSkylarkTypes.asDeepImmutable(dict);
    }
  }

  @Test
  public void asDeepImmutableFailsIfMutableValueIsPassedThatCannotBeMadeImmutable() {
    thrown.expect(MutableObjectException.class);
    BuckSkylarkTypes.asDeepImmutable(new FakeMutableSkylarkObject());
  }

  @Test
  public void asDeepImmutableFailsIfNonSkylarkValueNonPrimitiveTypeIsPassed() {
    thrown.expect(MutableObjectException.class);
    BuckSkylarkTypes.asDeepImmutable(ImmutableList.of());
  }

  @Test
  public void validateKwargNameHandlesValidNames() throws EvalException {
    Location location = Location.fromPathFragment(PathFragment.create("foo"));
    BuckSkylarkTypes.validateKwargName(location, "foo");
    BuckSkylarkTypes.validateKwargName(location, "foo_bar");
    BuckSkylarkTypes.validateKwargName(location, "foo_bar1");
    BuckSkylarkTypes.validateKwargName(location, "_foo");
    BuckSkylarkTypes.validateKwargName(location, "_foo_bar2");
  }

  @Test
  public void validateKwargNameRejectsEmpty() throws EvalException {
    thrown.expect(EvalException.class);
    BuckSkylarkTypes.validateKwargName(Location.fromPathFragment(PathFragment.create("foo")), "");
  }

  @Test
  public void validateKwargNameRejectsHyphenated() throws EvalException {
    thrown.expect(EvalException.class);
    BuckSkylarkTypes.validateKwargName(
        Location.fromPathFragment(PathFragment.create("foo")), "foo-bar");
  }

  @Test
  public void skylarkValueFromNullableReturnsNoneOnNull() {
    assertSame(Runtime.NONE, BuckSkylarkTypes.skylarkValueFromNullable(null));
  }

  @Test
  public void skylarkValueFromNullableReturnsOriginalObjectOnNonNull() {
    String someString = "foo";
    assertSame(someString, BuckSkylarkTypes.skylarkValueFromNullable(someString));
  }

  @Test
  public void optionalFromNoneOrType() throws EvalException {
    String foo = "foo";
    assertSame(foo, BuckSkylarkTypes.validateNoneOrType(Location.BUILTIN, String.class, foo));
    assertSame(
        Runtime.NONE,
        BuckSkylarkTypes.validateNoneOrType(Location.BUILTIN, String.class, Runtime.NONE));
    thrown.expect(EvalException.class);
    thrown.expectMessage("Invalid type provided");
    BuckSkylarkTypes.validateNoneOrType(Location.BUILTIN, String.class, 1);
  }

  @Test
  public void isImmutableWorks() {
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableSet.of("foo", 1, true)));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableSet.of(ImmutableList.of("list1", "list2"))));

    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableMap.of("foo", "bar")));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableMap.of("foo", 1)));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableMap.of("foo", true)));
    assertTrue(
        BuckSkylarkTypes.isImmutable(
            ImmutableMap.of(
                "foo", SkylarkList.createImmutable(ImmutableList.of("list1", "list2")))));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableMap.of("foo", SkylarkDict.empty())));
    assertTrue(
        BuckSkylarkTypes.isImmutable(ImmutableMap.of("foo", ImmutableList.of("list1", "list2"))));
    assertTrue(
        BuckSkylarkTypes.isImmutable(ImmutableMap.of("foo", ImmutableMap.of("key", "value"))));

    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableList.of("bar")));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableList.of(1)));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableList.of(true)));
    assertTrue(
        BuckSkylarkTypes.isImmutable(
            ImmutableList.of(SkylarkList.createImmutable(ImmutableList.of("list1", "list2")))));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableList.of(SkylarkDict.empty())));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableList.of(ImmutableList.of("list1", "list2"))));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableList.of(ImmutableMap.of("key", "value"))));

    assertTrue(BuckSkylarkTypes.isImmutable("bar"));
    assertTrue(BuckSkylarkTypes.isImmutable(1));
    assertTrue(BuckSkylarkTypes.isImmutable(true));
    assertTrue(
        BuckSkylarkTypes.isImmutable(
            SkylarkList.createImmutable(ImmutableList.of("list1", "list2"))));
    assertTrue(BuckSkylarkTypes.isImmutable(SkylarkDict.empty()));

    SkylarkList mutableList;
    SkylarkDict mutableDict;
    try (TestMutableEnv env = new TestMutableEnv(ImmutableMap.of())) {
      mutableList = SkylarkList.MutableList.of(env.getEnv(), 1, 2, 3);
      mutableDict = SkylarkDict.of(env.getEnv(), "key1", "val1");

      assertFalse(BuckSkylarkTypes.isImmutable(mutableList));
      assertFalse(BuckSkylarkTypes.isImmutable(mutableDict));
      assertFalse(BuckSkylarkTypes.isImmutable(ImmutableList.of(1, mutableList)));
      assertFalse(BuckSkylarkTypes.isImmutable(ImmutableList.of(1, mutableDict)));
      assertFalse(BuckSkylarkTypes.isImmutable(ImmutableSet.of(1, mutableDict)));
      assertFalse(BuckSkylarkTypes.isImmutable(ImmutableMap.of("k1", 1, "k2", mutableList)));
      assertFalse(BuckSkylarkTypes.isImmutable(ImmutableMap.of("k1", 1, "k2", mutableDict)));
    }

    assertTrue(BuckSkylarkTypes.isImmutable(mutableList));
    assertTrue(BuckSkylarkTypes.isImmutable(mutableDict));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableList.of(1, mutableList)));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableList.of(1, mutableDict)));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableSet.of(1, mutableDict)));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableMap.of("k1", 1, "k2", mutableList)));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableMap.of("k1", 1, "k2", mutableDict)));
  }
}
