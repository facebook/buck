/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.test.selectors.TestSelectorParseException;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.OptionHandler;
import org.kohsuke.args4j.spi.Parameters;
import org.kohsuke.args4j.spi.Setter;

public class TestSelectorOptions {

  @Option(
      name = "--test-selectors",
      aliases = {"--filter", "-f"},
      usage =
          "Select tests to run using <class>, #<method> or <class>#<method>.  " +
          "Selectors are interpreted java.util.regex regular expressions.  " +
          "If selectors are given, test result caching is disabled.  " +
          "If the class (or method) part is omitted, all classes (or methods) will match.  " +
          "If both the class and method is omitted (the string '#') then all tests will match.  " +
          "Prefix a selector with '!' to exclude a class or method.  " +
          "If multiple selectors are given, the first matching selector is used " +
          "to include (or exclude) a test.  " +
          "By default, all tests are excluded unless a selector includes them.  " +
          "However, if all selectors are exclusive then the default is to include.  " +
          "Use the format '@/path/to/file' to load selectors, one per line, from a file.  " +
          "Examples: 'com.example.MyTest' to run all tests in MyTest; " +
          "'com.example.MyTest#testFoo' or 'MyTest#Foo' to just run the testFoo test; " +
          "'!MyTest#Foo' to run everything except the testFoo test; " +
          "'#Important !TestA !TestC #' to only run the important tests in TestA and TestC " +
          "and run everything else.)",
      handler = TestSelectorsOptionHandler.class)
  public Supplier<TestSelectorList> testSelectorListSupplier;

  @Option(
      name = "--explain-test-selectors",
      usage = "Buck will say how it interpreted your test selectors before running tests.")
  private boolean shouldExplain = false;

  public TestSelectorList getTestSelectorList() {
    return testSelectorListSupplier.get();
  }

  public boolean shouldExplain() {
    return shouldExplain;
  }

  public static class TestSelectorsOptionHandler extends OptionHandler<Supplier<TestSelectorList>> {

    private final TestSelectorList.Builder builder = TestSelectorList.builder();

    public TestSelectorsOptionHandler(
        CmdLineParser parser,
        OptionDef option,
        Setter<Supplier<TestSelectorList>> setter) throws CmdLineException {
      super(parser, option, setter);
      setter.addValue(Suppliers.memoize(new Supplier<TestSelectorList>() {
        @Override
        public TestSelectorList get() {
          return builder.build();
        }
      }));
    }

    @Override
    public int parseArguments(Parameters parameters) throws CmdLineException {
      String rawTestSelector = parameters.getParameter(0);
      try {
        builder.addRawSelectors(rawTestSelector);
      } catch (TestSelectorParseException e) {
        String message = "Unable to parse test selectors: " + e.getMessage();
        throw new HumanReadableException(e, message);
      }
      return 1;
    }

    @Override
    public String getDefaultMetaVariable() {
      return "TEST-SELECTOR ";
    }
  }
}
