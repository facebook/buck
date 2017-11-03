// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.compatdx.CompatDx;
import com.google.common.collect.ImmutableList;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;

public final class D8Logger {

  private static final int STATUS_ERROR = 1;

  private static final String USAGE_MESSAGE = String.join("\n", ImmutableList.of(
      "Usage: java -jar d8logger.jar <compiler-options>",
      " where <compiler-options> will be",
      "",
      " 1. forwarded to the 'd8' or 'compatdx' tool (depending on the presence of the '--dex'",
      "    option), and also",
      " 2. appended to the file in the environment variable 'D8LOGGER_OUTPUT'",
      "",
      " The options will be appended as a new line with TAB characters between the arguments."));

  public static void main(String[] args) throws IOException {
    if (args.length == 0) {
      System.err.println(USAGE_MESSAGE);
      System.exit(STATUS_ERROR);
    }
    String output = System.getenv("D8LOGGER_OUTPUT");
    if (output == null) {
      throw new IOException("D8Logger: D8LOGGER_OUTPUT environment variable must be set.");
    }

    if (output.length() > 0) {
      String[] absArgs = Arrays.stream(args)
          .map(s -> s.startsWith("-") ? s : Paths.get(s).toAbsolutePath().toString())
          .toArray(String[]::new);
      FileWriter fw = new FileWriter(output, true);
      fw.write(String.join("\t", absArgs) + "\n");
      fw.close();
    }

    if (Arrays.stream(args).anyMatch(s -> s.equals("--dex"))) {
      CompatDx.main(args);
    } else {
      D8.main(args);
    }
  }
}
