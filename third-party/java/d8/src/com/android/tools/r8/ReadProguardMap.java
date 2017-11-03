// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.utils.Timing;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Benchmark for testing speed of parsing Proguard mapping files.
 */
public class ReadProguardMap {

  private static final String DEFAULT_MAP_FILE_NAME = "third_party/gmscore/v5/proguard.map";

  final Timing timing = new Timing("ReadProguardMap");

  private void readProguardMapFile(String fileName) {
    try {
      System.out.println("  - reading " + fileName);
      timing.begin("Reading " + fileName);
      ClassNameMapper.mapperFromFile(Paths.get(fileName));
      timing.end();
    } catch (IOException e) {
      System.err.print("Failed to parse Proguard mapping file: " + e.getMessage());
    }
  }

  public static void main(String[] args) {
    new ReadProguardMap().run(args);
  }

  private void run(String[] args) {
    System.out.println("ReadProguardMap benchmark.");
    if (args.length == 0) {
      readProguardMapFile(DEFAULT_MAP_FILE_NAME);
    } else {
      Arrays.asList(args).forEach((String name) -> readProguardMapFile(name));
    }
    timing.report();
  }
}
