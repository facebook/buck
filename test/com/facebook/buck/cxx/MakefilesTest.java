/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertThat;

import com.facebook.buck.model.Pair;
import com.google.common.collect.ImmutableList;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;

public class MakefilesTest {

  private static final ImmutableList<Pair<String, Makefiles.Makefile>> DATA =
      ImmutableList.of(
          new Pair<>(
              "output: input1 input2\n",
              new Makefiles.Makefile(
                  ImmutableList.of(
                      new Makefiles.Rule("output", ImmutableList.of("input1", "input2"))))),
          new Pair<>(
              "output: input1 \\\n input2\n",
              new Makefiles.Makefile(
                  ImmutableList.of(
                      new Makefiles.Rule("output", ImmutableList.of("input1", "input2"))))),
          new Pair<>(
              "output: input\\\\with\\\\slashes\n",
              new Makefiles.Makefile(
                  ImmutableList.of(
                      new Makefiles.Rule("output", ImmutableList.of("input\\\\with\\\\slashes"))))),
          new Pair<>(
              "output: input\\\\\\:\\\\\\ escape_chain\n",
              new Makefiles.Makefile(
                  ImmutableList.of(
                      new Makefiles.Rule("output", ImmutableList.of("input\\:\\ escape_chain"))))),
          new Pair<>(
              "output: input\\ with\\ spaces\n",
              new Makefiles.Makefile(
                  ImmutableList.of(
                      new Makefiles.Rule("output", ImmutableList.of("input with spaces"))))));

  @Test
  public void test() throws IOException {
    for (Pair<String, Makefiles.Makefile> sample : DATA) {
      assertThat(
          Makefiles.parseMakefile(new StringReader(sample.getFirst())),
          Matchers.equalTo(sample.getSecond()));
    }
  }

}
