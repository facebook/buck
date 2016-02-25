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

package com.facebook.buck.jvm.java.autodeps;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.util.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;

public class SymbolsTest {

  @Test
  public void jsonSerializatonAndDeserialization() throws IOException {
    // Symbols takes Iterables as parameters, so we make one a Set and one a List.
    // Note that because these are immutable collections from Guava, iteration order is guaranteed.
    ImmutableSet<String> providedSymbols = ImmutableSet.of(
        "com.example.Example1",
        "com.example.Example2",
        "com.example.Example3");
    ImmutableList<String> requiredSymbols = ImmutableList.of(
        "com.example.Required1",
        "com.example.Required2",
        "com.example.Required3");
    Symbols symbols = new Symbols(providedSymbols, requiredSymbols);

    ObjectMapper objectMapper = ObjectMappers.newDefaultInstance();
    StringWriter writer = new StringWriter();
    objectMapper.writeValue(writer, symbols);

    assertEquals(
        "{\"provided\":" +
            "[\"com.example.Example1\",\"com.example.Example2\",\"com.example.Example3\"]," +
            "\"required\":" +
            "[\"com.example.Required1\",\"com.example.Required2\",\"com.example.Required3\"]}",
        writer.toString()
    );

    Symbols restoredSymbols = objectMapper.readValue(writer.toString(), Symbols.class);
    // We compare using lists to ensure order was preserved.
    assertEquals(
        ImmutableList.copyOf(providedSymbols),
        ImmutableList.copyOf(restoredSymbols.provided));
    assertEquals(requiredSymbols, ImmutableList.copyOf(restoredSymbols.required));
  }
}
