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

package com.facebook.buck.skylark.function;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkList;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class JsonPrinterTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void noneIsPrintedAsNull() throws Exception {
    assertEquals("null", print(Runtime.NONE));
  }

  @Test
  public void structIsPrintedAsObject() throws Exception {
    assertEquals(
        "{\"foo\":\"bar\"}",
        print(StructProvider.STRUCT.create(ImmutableMap.of("foo", "bar"), "")));
  }

  @Test
  public void nestedStructIsPrintedAsANestedObject() throws Exception {
    assertEquals(
        "{\"foo\":{\"key\":\"value\"}}",
        print(
            StructProvider.STRUCT.create(
                ImmutableMap.of(
                    "foo", StructProvider.STRUCT.create(ImmutableMap.of("key", "value"), "")),
                "")));
  }

  @Test
  public void listIsPrintedAsList() throws Exception {
    assertEquals("[\"foo\",4]", print(SkylarkList.createImmutable(Arrays.asList("foo", 4))));
  }

  @Test
  public void stringIsPrintedAsString() throws Exception {
    assertEquals("\"string\"", print("string"));
  }

  @Test
  public void numberIsPrintedAsNumber() throws Exception {
    assertEquals("4", print(4));
  }

  @Test
  public void booleanIsPrintedAsBoolean() throws Exception {
    assertEquals("true", print(true));
  }

  @Test
  public void attemptToPrintUnsupportedTypeResultsInException() throws Exception {
    expectedException.expect(EvalException.class);
    expectedException.expectMessage(
        "Invalid text format, expected a struct, a string, a bool, or an int but got a java.lang.Object for struct field");
    print(new Object());
  }

  private static <T> String print(T value) throws EvalException {
    return JsonPrinter.printJson(value, Location.BUILTIN);
  }
}
