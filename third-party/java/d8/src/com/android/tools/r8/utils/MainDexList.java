// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import static com.android.tools.r8.utils.DescriptorUtils.JAVA_PACKAGE_SEPARATOR;
import static com.android.tools.r8.utils.FileUtils.CLASS_EXTENSION;

import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexType;
import com.google.common.collect.Sets;
import com.google.common.io.Closer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public class MainDexList {

  public static Set<DexType> parse(Path path, DexItemFactory itemFactory) throws IOException {
    try (Closer closer = Closer.create()) {
      return parse(closer.register(Files.newInputStream(path)), itemFactory);
    }
  }

  public static DexType parse(String clazz, DexItemFactory itemFactory) {
    if (!clazz.endsWith(CLASS_EXTENSION)) {
      throw new CompilationError("Illegal main-dex-list entry '" + clazz + "'.");
    }
    String name = clazz.substring(0, clazz.length() - CLASS_EXTENSION.length());
    if (name.contains("" + JAVA_PACKAGE_SEPARATOR)) {
      throw new CompilationError("Illegal main-dex-list entry '" + clazz + "'.");
    }
    String descriptor = "L" + name + ";";
    return itemFactory.createType(descriptor);
  }

  public static Set<DexType> parse(InputStream input, DexItemFactory itemFactory) {
    Set<DexType> result = Sets.newIdentityHashSet();
    try {
      BufferedReader file =
          new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
      String line;
      while ((line = file.readLine()) != null) {
        line = line.trim();
        if (line.length() > 0) {
          result.add(parse(line, itemFactory));
        }
      }
    } catch (IOException e) {
      throw new CompilationError("Cannot load main-dex-list.");
    }
    return result;
  }
}
