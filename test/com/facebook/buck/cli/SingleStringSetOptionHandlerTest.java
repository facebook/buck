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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

public class SingleStringSetOptionHandlerTest {
  private class TestBean {
    @Option(name = "--set", handler = SingleStringSetOptionHandler.class)
    Supplier<ImmutableSet<String>> someSet;

    @Option(name = "--other")
    String otherString = null;

    @Argument() List<String> nargs = new ArrayList<>();
  }

  @Rule public ExpectedException expected = ExpectedException.none();

  @Test
  public void createsSetWithoutConsumingOtherArgs() throws CmdLineException {
    TestBean bean = new TestBean();
    CmdLineParser parser = new CmdLineParser(bean);
    parser.parseArgument("--set", "a", "b", "c");
    assertEquals(ImmutableSet.of("a"), bean.someSet.get());
    assertEquals(ImmutableList.of("b", "c"), bean.nargs);
    assertNull(bean.otherString);
  }

  @Test
  public void handlesMultipleSpecificationsOfSet() throws CmdLineException {
    TestBean bean = new TestBean();
    CmdLineParser parser = new CmdLineParser(bean);
    parser.parseArgument("--set", "a", "--set", "b", "c", "d", "--set", "e", "f");
    assertEquals(ImmutableSet.of("a", "b", "e"), bean.someSet.get());
    assertEquals(ImmutableList.of("c", "d", "f"), bean.nargs);
    assertNull(bean.otherString);
  }

  @Test
  public void failsIfNoValueGiven() throws CmdLineException {
    expected.expect(CmdLineException.class);
    expected.expectMessage("Option \"--set\" takes an operand");
    TestBean bean = new TestBean();
    CmdLineParser parser = new CmdLineParser(bean);
    parser.parseArgument("--set");
  }

  @Test
  public void failsIfNoValueBeforeNextOption() throws CmdLineException {
    expected.expect(CmdLineException.class);
    expected.expectMessage("Option \"--set\" takes one operand");
    TestBean bean = new TestBean();
    CmdLineParser parser = new CmdLineParser(bean);
    parser.parseArgument("--set", "--other", "a", "b", "c");
  }
}
