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

import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class JsonTypeConcatenatingCoercerFactoryTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testCreateReturnsCorrectCoercerForInt() {
    assertTrue(
        JsonTypeConcatenatingCoercerFactory.createForType(Integer.class)
            instanceof IntConcatenatingCoercer);
  }

  @Test
  public void testCreateReturnsCorrectCoercerForString() {
    assertTrue(
        JsonTypeConcatenatingCoercerFactory.createForType(String.class)
            instanceof StringConcatenatingCoercer);
  }

  @Test
  public void testCreateReturnsCorrectCoercerForMap() {
    assertTrue(
        JsonTypeConcatenatingCoercerFactory.createForType(Map.class)
            instanceof MapConcatenatingCoercer);
  }

  @Test
  public void testCreateReturnsCorrectCoercerForList() {
    assertTrue(
        JsonTypeConcatenatingCoercerFactory.createForType(List.class)
            instanceof ListConcatenatingCoercer);
  }

  @Test
  public void testCreateReturnsSingleElementCoercerForUnsupportedType() {
    assertTrue(
        JsonTypeConcatenatingCoercerFactory.createForType(Boolean.class)
            instanceof SingleElementJsonTypeConcatenatingCoercer);
  }
}
