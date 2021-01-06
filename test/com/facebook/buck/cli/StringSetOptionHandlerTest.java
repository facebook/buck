/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableSet;
import java.util.function.Supplier;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

public class StringSetOptionHandlerTest {

  private class TestBean {
    @Option(name = "--option1", handler = StringSetOptionHandler.class)
    private Supplier<ImmutableSet<String>> option1;

    @Option(name = "--option2", handler = StringSetOptionHandler.class)
    private Supplier<ImmutableSet<String>> option2;

    public ImmutableSet<String> getOption1() {
      return option1.get();
    }

    public ImmutableSet<String> getOption2() {
      return option2.get();
    }
  }

  @Test
  public void testDefaultValues() throws CmdLineException {
    TestBean bean = new TestBean();
    CmdLineParser parser = new CmdLineParser(bean);
    parser.parseArgument("--option2", "a", "b");
    assertEquals(ImmutableSet.of(), bean.getOption1());
    assertEquals(ImmutableSet.of("a", "b"), bean.getOption2());
  }

  @Test
  public void testOptionSpecifiedMultipleTimes() throws CmdLineException {
    TestBean bean = new TestBean();
    CmdLineParser parser = new CmdLineParser(bean);
    parser.parseArgument(
        "--option1", "a", "b", "--option2", "c", "d", "--option1", "e", "f", "--option2", "g", "h");
    assertEquals(ImmutableSet.of("a", "b", "e", "f"), bean.getOption1());
    assertEquals(ImmutableSet.of("c", "d", "g", "h"), bean.getOption2());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testEmptyOption() throws CmdLineException {
    TestBean bean = new TestBean();
    CmdLineParser parser = new CmdLineParser(bean);
    parser.parseArgument("--option1", "--option2", "c", "d", "--option2", "f");
  }

  @Test(expected = IllegalArgumentException.class)
  public void testOptionSpecifiedWithoutElements() throws CmdLineException {
    TestBean bean = new TestBean();
    CmdLineParser parser = new CmdLineParser(bean);
    parser.parseArgument("--option1", "a", "--option2", "c", "d", "--option1", "--option2", "f");
  }

  @Test
  public void testOptionSpecifiedWithEmptyElements() throws CmdLineException {
    TestBean bean = new TestBean();
    CmdLineParser parser = new CmdLineParser(bean);
    parser.parseArgument("--option1", "", "a");
    assertEquals(ImmutableSet.of("", "a"), bean.getOption1());
  }
}
