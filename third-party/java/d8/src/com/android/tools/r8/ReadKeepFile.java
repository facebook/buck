// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.shaking.ProguardConfigurationParser;
import com.android.tools.r8.shaking.ProguardRuleParserException;
import com.android.tools.r8.utils.DefaultDiagnosticsHandler;
import com.android.tools.r8.utils.Timing;
import java.io.IOException;
import java.nio.file.Paths;

/**
 * Benchmark for testing ability to and speed of parsing Proguard keep files.
 */
public class ReadKeepFile {

  private static final String DEFAULT_KEEP_FILE_NAME = "build/proguard.cfg";

  final Timing timing = new Timing("ReadKeepFile");

  private void readProguardKeepFile(String fileName) throws ProguardRuleParserException {
    try {
      System.out.println("  - reading " + fileName);
      timing.begin("Reading " + fileName);
      new ProguardConfigurationParser(new DexItemFactory(), new DefaultDiagnosticsHandler())
          .parse(Paths.get(fileName));
      timing.end();
    } catch (IOException e) {
      System.err.print("Failed to parse Proguard keep file: " + e.getMessage());
    }
  }

  public static void main(String[] args) throws ProguardRuleParserException {
    new ReadKeepFile().run(args);
  }

  private void run(String[] args) throws ProguardRuleParserException {
    System.out.println("ProguardKeepRuleParser benchmark.");
    if (args.length == 0) {
      readProguardKeepFile(DEFAULT_KEEP_FILE_NAME);
    } else {
      for (String name : args) {
        readProguardKeepFile(name);
      }
    }
    timing.report();
  }
}
