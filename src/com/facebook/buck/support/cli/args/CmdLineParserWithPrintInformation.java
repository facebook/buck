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

package com.facebook.buck.support.cli.args;

import java.io.Writer;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.OptionHandlerFilter;
import org.kohsuke.args4j.spi.OptionHandler;

/**
 * An implementation of {@link CmdLineParser} that can provide some information that can be used to
 * print help in more flexible form than {@link CmdLineParser}.
 */
public class CmdLineParserWithPrintInformation extends CmdLineParser {

  public CmdLineParserWithPrintInformation(Object bean) {
    super(bean);
  }

  /** @return the maximum length of the options and arguments. */
  public int calculateMaxLen() {
    int len = 0;
    for (OptionHandler<?> h : getArguments()) {
      int curLen = getPrefixLen(h);
      len = Math.max(len, curLen);
    }
    for (OptionHandler<?> h : getOptions()) {
      int curLen = getPrefixLen(h);
      len = Math.max(len, curLen);
    }
    return len;
  }

  private int getPrefixLen(OptionHandler<?> h) {
    if (h.option.usage().isEmpty()) {
      return 0;
    }

    return h.getNameAndMeta(null, getProperties()).length();
  }

  /**
   * Prints usage using the provided length to print options (instead of calculating the length as
   * in {@link CmdLineParser}).
   */
  @SuppressWarnings("PMD.BlacklistedPrintWriterUsage")
  public void printUsage(Writer out, OptionHandlerFilter filter, int len) {
    java.io.PrintWriter w = new java.io.PrintWriter(out);

    for (OptionHandler<?> h : getArguments()) {
      printOption(w, h, len, null, filter);
    }
    for (OptionHandler<?> h : getOptions()) {
      printOption(w, h, len, null, filter);
    }

    w.flush();
  }
}
