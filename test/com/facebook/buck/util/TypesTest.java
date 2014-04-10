/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.util;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Calendar;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public class TypesTest {

  @Test
  public void canDetermineBaseTypeOfAPlainField() throws NoSuchFieldException {
    @SuppressWarnings("unused")
    class Contained {
      public String field;
    }

    Field field = Contained.class.getField("field");
    Type baseType = Types.getBaseType(field);

    assertEquals(String.class, baseType);
  }

  @Test
  public void canUnwrapAnOptionalToDetermineBaseType() throws NoSuchFieldException {
    @SuppressWarnings("unused")
    class Contained {
      public Optional<String> field;
    }

    Field field = Contained.class.getField("field");
    Type baseType = Types.getBaseType(field);

    assertEquals(String.class, baseType);
  }

  @Test
  public void canUnwrapACollectionToDetermineBaseType() throws NoSuchFieldException {
    @SuppressWarnings("unused")
    class Contained {
      public Set<String> field;
    }

    Field field = Contained.class.getField("field");
    Type baseType = Types.getBaseType(field);

    assertEquals(String.class, baseType);
  }

  @Test
  public void canDetermineContainedTypeOfACollection() throws NoSuchFieldException {
    @SuppressWarnings("unused")
    class Contained {
      public Set<? extends Calendar> field;
    }

    Field field = Contained.class.getField("field");
    Type baseType = Types.getBaseType(field);

    assertEquals(Calendar.class, baseType);
  }

  @Test
  public void canUnwrapAWildcardedOptionalToDetermineBaseType() throws NoSuchFieldException {
    @SuppressWarnings("unused")
    class Contained {
      public Optional<? extends Calendar> field;
    }

    Field field = Contained.class.getField("field");
    Type baseType = Types.getBaseType(field);

    assertEquals(Calendar.class, baseType);
  }

  @Test
  public void canDetermineContainedTypeOfAnOptionalCollection() throws NoSuchFieldException {
    @SuppressWarnings("unused")
    class Contained {
      public Optional<Set<? extends Calendar>> field;
    }

    Field field = Contained.class.getField("field");
    Type baseType = Types.getBaseType(field);

    assertEquals(Calendar.class, baseType);
  }

  @Test
  public void lowerBoundOfGenericTypeOfBaseTypeIsIgnored() throws NoSuchFieldException {
    @SuppressWarnings("unused")
    class Contained {
      public Comparable<? super String> field;
    }

    Field field = Contained.class.getField("field");
    Type baseType = Types.getBaseType(field);

    assertEquals(Object.class, baseType);
  }

  @Test
  public void baseTypeShouldWrapPrimitiveTypes() throws NoSuchFieldException {
    @SuppressWarnings("unused")
    class Contained {
      public int field;
    }

    Field field = Contained.class.getField("field");
    Type baseType = Types.getBaseType(field);

    assertEquals(Integer.class, baseType);

  }

  @Test
  public void shouldReturnNullContainerTypeForNonContainedFields() throws NoSuchFieldException {
    @SuppressWarnings("unused")
    class Contained {
      public String field;
    }

    Field field = Contained.class.getField("field");
    Class<? extends Collection<?>> container = Types.getContainerClass(field);

    assertNull(container);
  }

  @Test
  public void shouldReturnNullContainerTypeForNonContainedOptionalFields()
      throws NoSuchFieldException {
    @SuppressWarnings("unused")
    class Contained {
      public Optional<String> field;
    }

    Field field = Contained.class.getField("field");
    Class<? extends Collection<?>> container = Types.getContainerClass(field);

    assertNull(container);
  }

  @Test
  public void shouldReturnNullContainerForNonCollectionGenericFields() throws NoSuchFieldException {
    @SuppressWarnings("unused")
    class Contained {
      public Comparable<String> field;
    }

    Field field = Contained.class.getField("field");
    Class<? extends Collection<?>> container = Types.getContainerClass(field);

    assertNull(container);
  }

  @Test
  public void shouldReturnTheCorrectContainerTypeForASet() throws NoSuchFieldException {
    @SuppressWarnings("unused")
    class Contained {
      public Set<? extends Calendar> field;
    }

    Field field = Contained.class.getField("field");
    Class<? extends Collection<?>> container = Types.getContainerClass(field);

    assertEquals(Set.class, container);
  }

  @Test
  public void shouldReturnTheCorrectContainerTypeForAnImmutableSet() throws NoSuchFieldException {
    @SuppressWarnings("unused")
    class Contained {
      public ImmutableSet<? extends Calendar> field;
    }

    Field field = Contained.class.getField("field");
    Class<? extends Collection<?>> container = Types.getContainerClass(field);

    assertEquals(ImmutableSet.class, container);
  }

  @Test
  public void shouldReturnTheCorrectContainerTypeForAList() throws NoSuchFieldException {
    @SuppressWarnings("unused")
    class Contained {
      public List<String> field;
    }

    Field field = Contained.class.getField("field");
    Class<? extends Collection<?>> container = Types.getContainerClass(field);

    assertEquals(List.class, container);

  }

  @Test
  public void shouldReturnTheCorrectContainerTypeForAnImmutableList() throws NoSuchFieldException {
    @SuppressWarnings("unused")
    class Contained {
      public ImmutableList<Object> field;
    }

    Field field = Contained.class.getField("field");
    Class<? extends Collection<?>> container = Types.getContainerClass(field);

    assertEquals(ImmutableList.class, container);
  }

  @Test
  public void shouldReturnTheCorrectContainerTypeForAnOptionalCollection()
      throws NoSuchFieldException {
    @SuppressWarnings("unused")
    class Contained {
      public Optional<ImmutableSortedSet<? extends Calendar>> field;
    }

    Field field = Contained.class.getField("field");
    Class<? extends Collection<?>> container = Types.getContainerClass(field);

    assertEquals(ImmutableSortedSet.class, container);
  }

  @Test
  public void shouldReturnFirstNonOptionalTypeOfAField() throws NoSuchFieldException {
    @SuppressWarnings("unused")
    class Contained {
      public Optional<ImmutableList<? extends String>> wildCardList;
      public Optional<ImmutableList<String>> list;
      public Optional<String> raw;

      public ImmutableList<? extends String> expectedWildCardType;
      public ImmutableList<String> expectedListType;
    }

    Field field = Contained.class.getField("wildCardList");
    Field expected = Contained.class.getField("expectedWildCardType");
    assertEquals(expected.getGenericType(), Types.getFirstNonOptionalType(field));

    field = Contained.class.getField("list");
    expected = Contained.class.getField("expectedListType");
    assertEquals(expected.getGenericType(), Types.getFirstNonOptionalType(field));

    field = Contained.class.getField("raw");
    assertEquals(String.class, Types.getFirstNonOptionalType(field));
  }
}
