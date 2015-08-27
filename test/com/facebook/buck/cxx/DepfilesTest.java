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

import com.google.common.collect.ImmutableList;

import org.hamcrest.Matchers;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.junit.runner.RunWith;
import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class DepfilesTest {
  @Parameters(name = "{index}: ({0})=({1}, {2})")
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][] {
          {
              "output: input1 input2\n",
              "output",
              ImmutableList.of("input1", "input2")
          },
          {
              "output: input1 input2\r\n",
              "output",
              ImmutableList.of("input1", "input2")
          },
          {
              "   output   :    input1    input2   \n",
              "output",
              ImmutableList.of("input1", "input2")
          },
          {
              "output: input1\\\n input2\n",
              "output",
              ImmutableList.of("input1", "input2")
          },
          {
              "output: input1\\\r\n input2\r\n",
              "output",
              ImmutableList.of("input1", "input2")
          },
          {
              "output\\:: input1 input2\n",
              "output:",
              ImmutableList.of("input1", "input2")
          },
          {
              "output\\:: input1 input2\r\n",
              "output:",
              ImmutableList.of("input1", "input2")
          },
          {
              "output: input1\\#flav input2\\#flav\n",
              "output",
              ImmutableList.of("input1#flav", "input2#flav")
          },
          {
              "output: input1\\#flav input2\\#flav\r\n",
              "output",
              ImmutableList.of("input1#flav", "input2#flav")
          },
          {
              "output: input1\\\\\\#flav input2\\\\\\#flav\n",
              "output",
              ImmutableList.of("input1\\#flav", "input2\\#flav")
          },
          {
              "output: input1\\\\\\#flav input2\\\\\\#flav\r\n",
              "output",
              ImmutableList.of("input1\\#flav", "input2\\#flav")
          },
          {
              "output: input1\\\\\\\\\\\\#flav input2\\\\\\\\\\#flav\n",
              "output",
              ImmutableList.of("input1\\\\#flav", "input2\\\\#flav")
          },
          {
              "output: input1\\\\\\\\\\#flav input2\\\\\\\\\\#flav\r\n",
              "output",
              ImmutableList.of("input1\\\\#flav", "input2\\\\#flav")
          },
          {
              "output: input1 \\\n input2\n",
              "output",
              ImmutableList.of("input1", "input2")
          },
          {
              "output: input1 \\\r\n input2\r\n",
              "output",
              ImmutableList.of("input1", "input2")
          },
          {
              "output: input\\\\with\\\\slashes\n",
              "output",
              ImmutableList.of("input\\\\with\\\\slashes")
          },
          {
              "output: input\\\\with\\\\slashes\r\n",
              "output",
              ImmutableList.of("input\\\\with\\\\slashes")
          },
          {
              "output: input\\\\\\:\\\\\\ escape_chain\n",
              "output",
              ImmutableList.of("input\\\\\\:\\ escape_chain")
          },
          {
              "output: input\\\\\\:\\\\\\ escape_chain\r\n",
              "output",
              ImmutableList.of("input\\\\\\:\\ escape_chain")
          },
          {
              "output: input\\\\\\:\\\\\\\\\\ escape_chain\n",
              "output",
              ImmutableList.of("input\\\\\\:\\\\ escape_chain")
          },
          {
              "output: input\\\\\\:\\\\\\\\\\ escape_chain\r\n",
              "output",
              ImmutableList.of("input\\\\\\:\\\\ escape_chain")
          },
          {
              "output: input\\ with\\ spaces\n",
              "output",
              ImmutableList.of("input with spaces")
          },
          {
              "output: input\\ with\\ spaces\r\n",
              "output",
              ImmutableList.of("input with spaces")
          },
          {
              "output: input\\\\\\ with\\\\\\ spaces\n",
              "output",
              ImmutableList.of("input\\ with\\ spaces")
          },
          {
              "output: input\\\\\\ with\\\\\\ spaces\r\n",
              "output",
              ImmutableList.of("input\\ with\\ spaces")
          },
          {
              "output: input\\\\\\\twith\\\\\\\ttabs\n",
              "output",
              ImmutableList.of("input\\\twith\\\ttabs")
          },
          {
              "output: input\\\\\\\twith\\\\\\\ttabs\r\n",
              "output",
              ImmutableList.of("input\\\twith\\\ttabs")
          }
      });
  }

  @Parameter(value = 0)
  public String input;

  @Parameter(value = 1)
  public String expectedTarget;

  @Parameter(value = 2)
  public ImmutableList<String> expectedPrereqs;

  @Test
  public void parseDepfile() throws IOException {
    assertThat(
          String.format(
              "[%s] should parse correctly",
              input),
          Depfiles.parseDepfile(new StringReader(input)),
          Matchers.equalTo(new Depfiles.Depfile(expectedTarget, expectedPrereqs)));
  }
}
