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

package com.facebook.buck.skylark.json;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.syntax.SkylarkNestedSet;
import org.junit.Before;
import org.junit.Test;

public class SkylarkModuleTest {

  private ObjectMapper mapper;

  @Before
  public void setUp() throws Exception {
    mapper = new ObjectMapper();
    mapper.registerModule(new SkylarkModule());
  }

  @Test
  public void canSerializeSkylarkNestedSet() throws Exception {
    SkylarkNestedSet depset =
        SkylarkNestedSet.of(
            String.class, NestedSetBuilder.<String>stableOrder().add("foo").add("bar").build());
    assertThat(mapper.writeValueAsString(depset), equalTo("[\"foo\",\"bar\"]"));
  }
}
