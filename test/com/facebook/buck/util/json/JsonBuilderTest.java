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

package com.facebook.buck.util.json;

import static org.junit.Assert.assertEquals;

import java.util.Optional;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;
import org.junit.Test;

public class JsonBuilderTest {
  @Test
  public void emptyObject() {
    assertEquals("{}", JsonBuilder.object().toString());
  }

  @Test
  public void emptyArray() {
    assertEquals("[]", JsonBuilder.array().toString());
  }

  @Test
  public void objectWithNull() {
    assertEquals("{\"apple\":null}", JsonBuilder.object().addNull("apple").toString());
  }

  @Test
  public void arrayWithNull() {
    assertEquals("[null]", JsonBuilder.array().addNull().toString());
  }

  @Test
  public void objectWithTrue() {
    assertEquals("{\"banana\":true}", JsonBuilder.object().addBoolean("banana", true).toString());
  }

  @Test
  public void arrayWithTrue() {
    assertEquals("[true]", JsonBuilder.array().addBoolean(true).toString());
  }

  @Test
  public void objectWithFalse() {
    assertEquals(
        "{\"clementine\":false}", JsonBuilder.object().addBoolean("clementine", false).toString());
  }

  @Test
  public void arrayWithFalse() {
    assertEquals("[false]", JsonBuilder.array().addBoolean(false).toString());
  }

  @Test
  public void objectWithNumber() {
    assertEquals(
        ("{\"dates\":-12.345}"), JsonBuilder.object().addNumber("dates", -12.345).toString());
  }

  @Test
  public void arrayWithNumber() {
    assertEquals(("[-12.345]"), JsonBuilder.array().addNumber(-12.345).toString());
  }

  @Test
  public void objectWithString() {
    assertEquals(
        ("{\"elderberry\":\"one\\ntwo\"}"),
        JsonBuilder.object().addString("elderberry", "one\ntwo").toString());
  }

  @Test
  public void arrayWithString() {
    assertEquals(("[\"one\\ntwo\"]"), JsonBuilder.array().addString("one\ntwo").toString());
  }

  @Test
  public void objectWithNestedObject() {
    assertEquals(
        "{\"fig\":{\"grape\":null}}",
        JsonBuilder.object().addObject("fig", JsonBuilder.object().addNull("grape")).toString());
  }

  @Test
  public void arrayWithNestedObject() {
    assertEquals(
        "[{\"hackberry\":null}]",
        JsonBuilder.array().addObject(JsonBuilder.object().addNull("hackberry")).toString());
  }

  @Test
  public void objectWithNestedArray() {
    assertEquals(
        "{\"imbe\":[null]}",
        JsonBuilder.object().addArray("imbe", JsonBuilder.array().addNull()).toString());
  }

  @Test
  public void arrayWithNestedArray() {
    assertEquals(
        "[[null]]", JsonBuilder.array().addArray(JsonBuilder.array().addNull()).toString());
  }

  @Test
  public void arrayCollectors() {
    assertEquals(
        "[\"a\",\"b\",\"c\"]",
        Stream.of("a", "b", "c").collect(JsonBuilder.toArrayOfStrings()).toString());

    assertEquals(
        "[{},{},{}]",
        Stream.of(JsonBuilder.object(), JsonBuilder.object(), JsonBuilder.object())
            .collect(JsonBuilder.toArrayOfObjects())
            .toString());

    assertEquals(
        "[[0.0,null],[1.0,[]],[2.0,{}]]",
        Stream.of(
                JsonBuilder.array().addNumber(0).addNull(),
                JsonBuilder.array().addNumber(1).addRaw("[]"),
                JsonBuilder.array().addNumber(2).addRaw("{}"))
            .collect(JsonBuilder.toArrayOfArrays())
            .toString());

    assertEquals("[1.0,2.0,3.0]", JsonBuilder.arrayOfDoubles(DoubleStream.of(1, 2, 3)).toString());
  }

  @Test
  public void mergeEmptyArrayBuilders() {
    assertEquals("[]", JsonBuilder.array().merge(JsonBuilder.array()).toString());
  }

  @Test
  public void mergingArrayBuilderWithEmpty() {
    assertEquals(
        "[1.0,2.0,3.0]",
        JsonBuilder.array()
            .addNumber(1)
            .addNumber(2)
            .addNumber(3)
            .merge(JsonBuilder.array())
            .toString());
  }

  @Test
  public void mergeEmptyArrayBuilderWithNonEmpty() {
    assertEquals(
        "[\"a\",\"b\",\"c\"]",
        JsonBuilder.array()
            .merge(JsonBuilder.array().addString("a").addString("b").addString("c"))
            .toString());
  }

  @Test
  public void mergeNonEmptyArrayBuilders() {
    assertEquals(
        "[1.0,2.0,3.0,\"a\",\"b\",\"c\"]",
        JsonBuilder.array()
            .addNumber(1)
            .addNumber(2)
            .addNumber(3)
            .merge(JsonBuilder.array().addString("a").addString("b").addString("c"))
            .toString());
  }

  @Test
  public void objectWithRawValue() {
    assertEquals(
        "{\"jambolan\":[1,\"2\",3]}",
        JsonBuilder.object().addRaw("jambolan", "[1,\"2\",3]").toString());
  }

  @Test
  public void arrayWithRawValue() {
    assertEquals("[[1,\"2\",3]]", JsonBuilder.array().addRaw("[1,\"2\",3]").toString());
  }

  @Test
  public void objectWithEmptyOptionals() {
    assertEquals(
        "{}",
        JsonBuilder.object()
            .addBoolean("kiwi", Optional.empty())
            .addNumber("lemon", Optional.empty())
            .addString("mango", Optional.empty())
            .addRaw("nectarine", Optional.empty())
            .toString());
  }

  @Test
  public void objectWithPresentOptionals() {
    assertEquals(
        "{\"orange\":false,\"peach\":42.0,\"quince\":\"jam\",\"raspberry\":[{},{}]}",
        JsonBuilder.object()
            .addBoolean("orange", Optional.of(false))
            .addNumber("peach", Optional.of(42.0))
            .addString("quince", Optional.of("jam"))
            .addRaw("raspberry", Optional.of("[{},{}]"))
            .toString());
  }

  @Test
  public void arrayWithEmptyOptionals() {
    assertEquals(
        "[null,null,null,null]",
        JsonBuilder.array()
            .addBoolean(Optional.empty())
            .addNumber(Optional.empty())
            .addString(Optional.empty())
            .addRaw(Optional.empty())
            .toString());
  }

  @Test
  public void arrayWithPresentOptionals() {
    assertEquals(
        "[false,42.0,\"jam\",[{},{}]]",
        JsonBuilder.array()
            .addBoolean(Optional.of(false))
            .addNumber(Optional.of(42.0))
            .addString(Optional.of("jam"))
            .addRaw(Optional.of("[{},{}]"))
            .toString());
  }

  @Test
  public void nested() {
    assertEquals(
        "{\"strawberry\":true,\"tangerine\":[1.0,\"2\",true,false,null,{}],\"watermelon\":[[[]]],\"xigua\":{}}",
        JsonBuilder.object()
            .addBoolean("strawberry", true)
            .addArray(
                "tangerine",
                JsonBuilder.array()
                    .addNumber(1)
                    .addString("2")
                    .merge(JsonBuilder.array().addBoolean(true).addBoolean(false))
                    .addBoolean(Optional.empty())
                    .addObject(JsonBuilder.object()))
            .addNumber("voavanga", Optional.empty())
            .addRaw("watermelon", "[[[]]]")
            .addObject("xigua", JsonBuilder.object())
            .toString());
  }
}
