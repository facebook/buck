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

package com.facebook.buck.rules.modern.impl;

import static org.junit.Assert.*;

import com.facebook.buck.rules.modern.Buildable;
import org.junit.Test;

public class StringifyingValueVisitorTest extends AbstractValueVisitorTest {
  @Override
  @Test
  public void outputPath() {
    assertEquals("output:OutputPath(some/path)\n", stringify(new WithOutputPath()));
  }

  @Override
  @Test
  public void sourcePath() {
    assertEquals("path:SourcePath(/project/root/some/path)\n", stringify(new WithSourcePath()));
  }

  @Override
  @Test
  public void set() {
    assertEquals("present:value([!, hello, world])\nempty:value([])\n", stringify(new WithSet()));
  }

  @Override
  @Test
  public void list() {
    assertEquals("present:value([hello, world, !])\nempty:value([])\n", stringify(new WithList()));
  }

  @Override
  @Test
  public void optional() {
    assertEquals(
        "present:value(Optional[hello])\nempty:value(Optional.empty)\n",
        stringify(new WithOptional()));
  }

  @Override
  @Test
  public void simple() {
    assertEquals("value:value(1)\n", stringify(new Simple()));
  }

  @Override
  @Test
  public void superClass() {
    assertEquals("value:value(1)\nnumber:value(2.3)\n", stringify(new Derived()));
  }

  @Override
  @Test
  public void empty() {
    assertEquals("", stringify(new Empty()));
  }

  @Override
  @Test
  public void complex() {
    assertEquals(
        "value:Optional<\n"
            + "  List<\n"
            + "    Set<\n"
            + "    >\n"
            + "    Set<\n"
            + "      SourcePath(//some/build:target)\n"
            + "      SourcePath(/project/root/some/path)\n"
            + "    >\n"
            + "  >\n"
            + ">\n"
            + "string:value(hello)\n"
            + "number:value(0)\n"
            + "outputs:List<\n"
            + "  OutputPath(hello.txt)\n"
            + "  OutputPath(world.txt)\n"
            + ">\n"
            + "otherOutput:OutputPath(other.file)\n",
        stringify(new Complex()));
  }

  private String stringify(Buildable value) {
    StringifyingValueVisitor visitor = new StringifyingValueVisitor();
    DefaultClassInfoFactory.forBuildable(value).visit(value, visitor);
    return visitor.getValue();
  }
}
