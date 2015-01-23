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

package com.facebook.buck.rules.coercer;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

public class BuildConfigFieldsTest {

  @Test
  public void testParseFields() {
    BuildConfigFields buildConfigValues = BuildConfigFields.fromFieldDeclarations(
        ImmutableList.of(
            "boolean DEBUG = false",
            "String KEYSTORE_TYPE = \"inhouse\"",
            "long MEANING_OF_LIFE = 42"));
    MoreAsserts.assertIterablesEquals(
        ImmutableList.of(
            ImmutableBuildConfigFields.Field.of("boolean", "DEBUG", "false"),
            ImmutableBuildConfigFields.Field.of("String", "KEYSTORE_TYPE", "\"inhouse\""),
            ImmutableBuildConfigFields.Field.of("long", "MEANING_OF_LIFE", "42")),
        buildConfigValues);
  }

  @Test
  public void testToString() {
    BuildConfigFields buildConfigValues = BuildConfigFields.fromFieldDeclarations(
        ImmutableList.of(
            "boolean DEBUG = false",
            "String KEYSTORE_TYPE = \"inhouse\"",
            "long MEANING_OF_LIFE = 42"));
    assertEquals(
        "boolean DEBUG = false;String KEYSTORE_TYPE = \"inhouse\";long MEANING_OF_LIFE = 42",
        buildConfigValues.toString());
  }

  @Test
  public void testFieldToString() {
    assertEquals(
        "String KEYSTORE_TYPE = \"inhouse\"",
        ImmutableBuildConfigFields.Field.of("String", "KEYSTORE_TYPE", "\"inhouse\"").toString());
  }
}
