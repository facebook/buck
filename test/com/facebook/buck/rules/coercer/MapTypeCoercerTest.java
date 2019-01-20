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
package com.facebook.buck.rules.coercer;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class MapTypeCoercerTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testConcatProducesMergedMaps() {
    MapTypeCoercer<String, String> coercer =
        new MapTypeCoercer<>(new StringTypeCoercer(), new StringTypeCoercer());

    assertEquals(
        ImmutableMap.of("a", "a", "b", "b", "c", "c", "d", "d", "e", "e"),
        coercer.concat(
            Arrays.asList(
                ImmutableMap.of("a", "a", "b", "b", "c", "c"),
                ImmutableMap.of("d", "d"),
                ImmutableMap.of("e", "e"))));
  }

  @Test
  public void testConcatThrowsExceptionWithDuplicateKeys() {
    MapTypeCoercer<String, String> coercer =
        new MapTypeCoercer<>(new StringTypeCoercer(), new StringTypeCoercer());

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("Duplicate key found when trying to concatenate a map attribute: a");

    coercer.concat(Arrays.asList(ImmutableMap.of("a", "a"), ImmutableMap.of("a", "b")));
  }
}
