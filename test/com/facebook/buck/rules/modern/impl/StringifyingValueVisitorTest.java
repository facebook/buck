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
    assertEquals("output:OutputPath(some/path)", stringify(new WithOutputPath()));
  }

  @Override
  @Test
  public void sourcePath() {
    assertEquals("path:SourcePath(/project/root/some/path)", stringify(new WithSourcePath()));
  }

  @Override
  @Test
  public void set() {
    assertEquals(
        "present:Set<\n"
            + "  string(!)\n"
            + "  string(hello)\n"
            + "  string(world)\n"
            + ">\n"
            + "empty:Set<\n"
            + ">",
        stringify(new WithSet()));
  }

  @Override
  @Test
  public void list() {
    assertEquals(
        "present:List<\n"
            + "  string(hello)\n"
            + "  string(world)\n"
            + "  string(!)\n"
            + ">\n"
            + "empty:List<\n"
            + ">",
        stringify(new WithList()));
  }

  @Override
  @Test
  public void optional() {
    assertEquals(
        "present:Optional<\n" + "  string(hello)\n" + ">\n" + "empty:Optional.empty()",
        stringify(new WithOptional()));
  }

  @Override
  @Test
  public void simple() {
    assertEquals(
        "string:string(string)\n"
            + "integer:integer(1)\n"
            + "character:character(c)\n"
            + "value:float(2.5)\n"
            + "doubles:List<\n"
            + "  double(1.1)\n"
            + "  double(2.2)\n"
            + "  double(3.3)\n"
            + ">",
        stringify(new Simple()));
  }

  @Override
  @Test
  public void superClass() {
    assertEquals(
        "string:string(string)\n"
            + "integer:integer(1)\n"
            + "character:character(c)\n"
            + "value:float(2.5)\n"
            + "doubles:List<\n"
            + "  double(1.1)\n"
            + "  double(2.2)\n"
            + "  double(3.3)\n"
            + ">\n"
            + "number:double(2.3)",
        stringify(new Derived()));
  }

  @Override
  @Test
  public void empty() {
    assertEquals("", stringify(new Empty()));
  }

  @Override
  @Test
  public void addsToRuleKey() {
    assertEquals(
        "nested:com.facebook.buck.rules.modern.impl.AbstractValueVisitorTest$NestedAppendable<\n"
            + "  appendable:Optional<\n"
            + "    com.facebook.buck.rules.modern.impl.AbstractValueVisitorTest$Appendable<\n"
            + "      sp:SourcePath(/project/root/appendable.path)\n"
            + "    >\n"
            + "  >\n"
            + ">\n"
            + "list:List<\n"
            + "  com.facebook.buck.rules.modern.impl.AbstractValueVisitorTest$Appendable<\n"
            + "    sp:SourcePath(/project/root/appendable.path)\n"
            + "  >\n"
            + "  com.facebook.buck.rules.modern.impl.AbstractValueVisitorTest$Appendable<\n"
            + "    sp:SourcePath(/project/root/appendable.path)\n"
            + "  >\n"
            + ">",
        stringify(new WithAddsToRuleKey()));
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
            + "string:string(hello)\n"
            + "number:integer(0)\n"
            + "outputs:List<\n"
            + "  OutputPath(hello.txt)\n"
            + "  OutputPath(world.txt)\n"
            + ">\n"
            + "otherOutput:OutputPath(other.file)\n"
            + "appendable:com.facebook.buck.rules.modern.impl.AbstractValueVisitorTest$Appendable<\n"
            + "  sp:SourcePath(/project/root/appendable.path)\n"
            + ">",
        stringify(new Complex()));
  }

  @Test
  @Override
  public void buildTarget() {
    assertEquals(
        "target:path(/project/other)Optional<\n"
            + "  string(other)\n"
            + ">string(//some)string(target)Set<\n"
            + "  string(flavor1)\n"
            + "  string(flavor2)\n"
            + ">",
        stringify(new WithBuildTarget()));
  }

  private String stringify(Buildable value) {
    StringifyingValueVisitor visitor = new StringifyingValueVisitor();
    DefaultClassInfoFactory.forInstance(value).visit(value, visitor);
    return visitor.getValue();
  }
}
