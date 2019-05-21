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

import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import junitparams.naming.TestCaseName;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class ObjectMappersTest {

  static class Obj {
    public Path path;
  }

  private Object getSerializeAndDeserializePathData() {
    List<String> paths = ImmutableList.of("", "/", "a", "a/b", "/a", "some/path", "/some/path");
    List<Boolean> typed = ImmutableList.of(true, false);

    return Lists.cartesianProduct(
            paths.stream().map(p -> Paths.get(p)).collect(Collectors.toList()), typed)
        .stream()
        .map(l -> l.toArray())
        .toArray();
  }

  @Test
  @Parameters(method = "getSerializeAndDeserializePathData")
  @TestCaseName("canSerializeAndDeserializePath({0}_{1})")
  public void canSerializeAndDeserializePath(Path path, boolean typed) throws Exception {
    Obj obj = new Obj();
    obj.path = path;

    ObjectWriter writer = typed ? ObjectMappers.WRITER_WITH_TYPE : ObjectMappers.WRITER;
    ObjectReader reader = typed ? ObjectMappers.READER_WITH_TYPE : ObjectMappers.READER;

    String data = writer.writeValueAsString(obj);

    Obj actual = reader.forType(Obj.class).readValue(data);

    assertEquals(path, actual.path);
  }
}
