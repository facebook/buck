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

import com.facebook.buck.skylark.function.packages.StarlarkInfo;
import com.facebook.buck.skylark.function.packages.StructProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Objects;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.Tuple;
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
    Tuple skylarkList = Tuple.of(1, 2, 3);

    thrown.expect(EvalException.class);
    BuckSkylarkTypes.toJavaList(skylarkList, FakeClass.class, null);
  }

  @Test
  public void toJavaListCastsGenericsProperly() throws EvalException {
    Tuple skylarkList =
        Tuple.<FakeClass<?>>of(new FakeClass<>("foo"), new FakeClass<>(1), new FakeClass<>(false));

    ImmutableList<FakeClass<?>> list =
        BuckSkylarkTypes.toJavaList(skylarkList, FakeClass.class, null);
    assertEquals(
        ImmutableList.of(new FakeClass<>("foo"), new FakeClass<>(1), new FakeClass<>(false)), list);
  }

  @Test
  public void toJavaListNonGenericsProperly() throws EvalException {
    Tuple skylarkList = Tuple.of(1, 2, 3);
    ImmutableList<Integer> list = BuckSkylarkTypes.toJavaList(skylarkList, Integer.class, null);

    assertEquals(ImmutableList.of(1, 2, 3), list);
  }

  @Test
  public void asDeepImmutableReturnsPrimitives() {
    StarlarkInt integer = StarlarkInt.of(1);
    String string = "some string";

    assertSame(integer, BuckSkylarkTypes.asDeepImmutable(integer));
    assertSame(string, BuckSkylarkTypes.asDeepImmutable(string));
    assertSame(true, BuckSkylarkTypes.asDeepImmutable(true));
  }

  @Test
  public void asDeepImmutableReturnsIdentityForImmutableSkylarkValues() {
    StarlarkList<String> list = StarlarkList.immutableCopyOf(ImmutableList.of("foo", "bar"));
    Dict<String, String> dict = Dict.immutableCopyOf(ImmutableMap.of("foo", "bar"));
    StarlarkInfo struct = StructProvider.STRUCT.create(ImmutableMap.of("foo", "bar"), "not found");

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
      StarlarkList<String> subList = StarlarkList.of(env.getEnv().mutability(), "foo", "bar");
      StarlarkList<StarlarkList<String>> list = StarlarkList.of(env.getEnv().mutability(), subList);

      Object result = BuckSkylarkTypes.asDeepImmutable(list);

      assertFalse(list.isImmutable());
      assertTrue(((StarlarkList<?>) result).isImmutable());
      assertEquals(list, result);
      assertNotSame(list, result);
    }
  }

  @Test
  public void asDeepImmutableReturnsListOfImmutablesIfAllElementsAreImmutable() {
    try (TestMutableEnv env = new TestMutableEnv()) {
      StarlarkList<String> list = StarlarkList.of(env.getEnv().mutability(), "foo", "bar");

      Object result = BuckSkylarkTypes.asDeepImmutable(list);

      assertFalse(list.isImmutable());
      assertTrue(((StarlarkList<?>) result).isImmutable());
      assertEquals(list, result);
      assertNotSame(list, result);
    }
  }

  @Test
  public void asDeepImmutableFailsIfListHasMutableElementThatCannotBeMadeImmutable() {
    try (TestMutableEnv env = new TestMutableEnv()) {
      FakeMutableSkylarkObject mutable = new FakeMutableSkylarkObject();
      StarlarkList<FakeMutableSkylarkObject> list =
          StarlarkList.of(env.getEnv().mutability(), mutable);

      thrown.expect(MutableObjectException.class);
      BuckSkylarkTypes.asDeepImmutable(list);
    }
  }

  @Test
  public void asDeepImmutableReturnsDictOfImmutablesIfKeyIsMutable() {
    try (TestMutableEnv env = new TestMutableEnv()) {
      StarlarkList<String> list = StarlarkList.of(env.getEnv().mutability(), "foo", "bar");
      Dict<StarlarkList<String>, String> dict =
          Dict.copyOf(env.getEnv().mutability(), ImmutableMap.of(list, "foo"));

      Object result = BuckSkylarkTypes.asDeepImmutable(dict);

      assertFalse(dict.isImmutable());
      assertTrue(((Dict<?, ?>) result).isImmutable());
      assertEquals(dict, result);
      assertNotSame(dict, result);
    }
  }

  @Test
  public void asDeepImmutableReturnsDictOfImmutablesIfValueIsMutable() {
    try (TestMutableEnv env = new TestMutableEnv()) {
      StarlarkList<String> list = StarlarkList.of(env.getEnv().mutability(), "foo", "bar");
      Dict<String, StarlarkList<String>> dict =
          Dict.copyOf(env.getEnv().mutability(), ImmutableMap.of("foo", list));

      Object result = BuckSkylarkTypes.asDeepImmutable(dict);

      assertFalse(dict.isImmutable());
      assertTrue(((Dict<?, ?>) result).isImmutable());
      assertEquals(dict, result);
      assertNotSame(dict, result);
    }
  }

  @Test
  public void asDeepImmutableReturnsDictOfImmutablesIfAllKeysAndValuesAreImmutable() {
    try (TestMutableEnv env = new TestMutableEnv()) {
      Dict<String, String> dict =
          Dict.copyOf(env.getEnv().mutability(), ImmutableMap.of("foo", "bar"));

      Object result = BuckSkylarkTypes.asDeepImmutable(dict);

      assertFalse(dict.isImmutable());
      assertTrue(((Dict<?, ?>) result).isImmutable());
      assertEquals(dict, result);
      assertNotSame(dict, result);
    }
  }

  @Test
  public void asDeepImmutableFailsIfDictHasMutableKeyThatCannotBeMadeImmutable() {
    try (TestMutableEnv env = new TestMutableEnv()) {
      Dict<FakeMutableSkylarkObject, String> dict =
          Dict.copyOf(
              env.getEnv().mutability(), ImmutableMap.of(new FakeMutableSkylarkObject(), "foo"));

      thrown.expect(MutableObjectException.class);
      BuckSkylarkTypes.asDeepImmutable(dict);
    }
  }

  @Test
  public void asDeepImmutableFailsIfDictHasMutableValueThatCannotBeMadeImmutable() {
    try (TestMutableEnv env = new TestMutableEnv()) {
      Dict<String, FakeMutableSkylarkObject> dict =
          Dict.copyOf(
              env.getEnv().mutability(), ImmutableMap.of("foo", new FakeMutableSkylarkObject()));

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
  public void validateKwargNameHandlesValidNames() throws EvalException {
    BuckSkylarkTypes.validateKwargName("foo");
    BuckSkylarkTypes.validateKwargName("foo_bar");
    BuckSkylarkTypes.validateKwargName("foo_bar1");
    BuckSkylarkTypes.validateKwargName("_foo");
    BuckSkylarkTypes.validateKwargName("_foo_bar2");
  }

  @Test
  public void validateKwargNameRejectsEmpty() throws EvalException {
    thrown.expect(EvalException.class);
    BuckSkylarkTypes.validateKwargName("");
  }

  @Test
  public void validateKwargNameRejectsHyphenated() throws EvalException {
    thrown.expect(EvalException.class);
    BuckSkylarkTypes.validateKwargName("foo-bar");
  }

  @Test
  public void skylarkValueFromNullableReturnsNoneOnNull() {
    assertSame(Starlark.NONE, BuckSkylarkTypes.skylarkValueFromNullable(null));
  }

  @Test
  public void skylarkValueFromNullableReturnsOriginalObjectOnNonNull() {
    String someString = "foo";
    assertSame(someString, BuckSkylarkTypes.skylarkValueFromNullable(someString));
  }

  @Test
  public void optionalFromNoneOrType() throws EvalException {
    String foo = "foo";
    assertSame(foo, BuckSkylarkTypes.validateNoneOrType(String.class, foo));
    assertSame(Starlark.NONE, BuckSkylarkTypes.validateNoneOrType(String.class, Starlark.NONE));
    thrown.expect(EvalException.class);
    thrown.expectMessage("Invalid type provided");
    BuckSkylarkTypes.validateNoneOrType(String.class, 1);
  }

  @Test
  public void isImmutableWorks() throws EvalException {
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableSet.of("foo", StarlarkInt.of(1), true)));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableSet.of(ImmutableList.of("list1", "list2"))));

    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableMap.of("foo", "bar")));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableMap.of("foo", StarlarkInt.of(1))));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableMap.of("foo", true)));
    assertTrue(
        BuckSkylarkTypes.isImmutable(
            ImmutableMap.of(
                "foo", StarlarkList.immutableCopyOf(ImmutableList.of("list1", "list2")))));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableMap.of("foo", Dict.empty())));
    assertTrue(
        BuckSkylarkTypes.isImmutable(ImmutableMap.of("foo", ImmutableList.of("list1", "list2"))));
    assertTrue(
        BuckSkylarkTypes.isImmutable(ImmutableMap.of("foo", ImmutableMap.of("key", "value"))));

    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableList.of("bar")));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableList.of(StarlarkInt.of(1))));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableList.of(true)));
    assertTrue(
        BuckSkylarkTypes.isImmutable(
            ImmutableList.of(StarlarkList.immutableCopyOf(ImmutableList.of("list1", "list2")))));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableList.of(Dict.empty())));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableList.of(ImmutableList.of("list1", "list2"))));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableList.of(ImmutableMap.of("key", "value"))));

    assertTrue(BuckSkylarkTypes.isImmutable("bar"));
    assertTrue(BuckSkylarkTypes.isImmutable(StarlarkInt.of(1)));
    assertTrue(BuckSkylarkTypes.isImmutable(true));
    assertTrue(
        BuckSkylarkTypes.isImmutable(
            StarlarkList.immutableCopyOf(ImmutableList.of("list1", "list2"))));
    assertTrue(BuckSkylarkTypes.isImmutable(Dict.empty()));

    StarlarkList mutableList;
    Dict mutableDict;
    try (TestMutableEnv env = new TestMutableEnv(ImmutableMap.of())) {
      mutableList =
          StarlarkList.of(
              env.getEnv().mutability(), StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(2));
      mutableDict = Dict.of(env.getEnv().mutability());
      mutableDict.putEntry("key1", "val1");

      assertFalse(BuckSkylarkTypes.isImmutable(mutableList));
      assertFalse(BuckSkylarkTypes.isImmutable(mutableDict));
      assertFalse(BuckSkylarkTypes.isImmutable(ImmutableList.of(StarlarkInt.of(1), mutableList)));
      assertFalse(BuckSkylarkTypes.isImmutable(ImmutableList.of(StarlarkInt.of(1), mutableDict)));
      assertFalse(BuckSkylarkTypes.isImmutable(ImmutableSet.of(StarlarkInt.of(1), mutableDict)));
      assertFalse(
          BuckSkylarkTypes.isImmutable(
              ImmutableMap.of("k1", StarlarkInt.of(1), "k2", mutableList)));
      assertFalse(
          BuckSkylarkTypes.isImmutable(
              ImmutableMap.of("k1", StarlarkInt.of(1), "k2", mutableDict)));
    }

    assertTrue(BuckSkylarkTypes.isImmutable(mutableList));
    assertTrue(BuckSkylarkTypes.isImmutable(mutableDict));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableList.of(StarlarkInt.of(1), mutableList)));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableList.of(StarlarkInt.of(1), mutableDict)));
    assertTrue(BuckSkylarkTypes.isImmutable(ImmutableSet.of(StarlarkInt.of(1), mutableDict)));
    assertTrue(
        BuckSkylarkTypes.isImmutable(ImmutableMap.of("k1", StarlarkInt.of(1), "k2", mutableList)));
    assertTrue(
        BuckSkylarkTypes.isImmutable(ImmutableMap.of("k1", StarlarkInt.of(1), "k2", mutableDict)));
  }
}
