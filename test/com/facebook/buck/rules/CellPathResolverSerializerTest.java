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
package com.facebook.buck.rules;

import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableMap;
import java.nio.file.Paths;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CellPathResolverSerializerTest {
  @Test
  public void testSerializingDefaultCellPathResolver() throws Exception {
    DefaultCellPathResolver input =
        new DefaultCellPathResolver(
            Paths.get("/root/path"),
            ImmutableMap.of(
                "key1", Paths.get("/path/1/"),
                "key_2", Paths.get("/path/_2/")));
    Map<String, Object> data = CellPathResolverSerializer.serialize(input);
    CellPathResolver output = CellPathResolverSerializer.deserialize(data);
    assertThat(output, Matchers.instanceOf(DefaultCellPathResolver.class));
    DefaultCellPathResolver defaultOutput = (DefaultCellPathResolver) output;
    assertThat(defaultOutput.getRoot(), Matchers.equalToObject(input.getRoot()));
    assertThat(defaultOutput.getCellPaths(), Matchers.equalToObject(input.getCellPaths()));
  }
}
