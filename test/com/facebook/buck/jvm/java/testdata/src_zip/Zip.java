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

package com.example;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.util.HashMap;
import java.util.Map;

/** Creates a zip file with entries for Main.java and Yang.java. */
public class Zip {

  /** @param args Should contain one value, which is a path to a zip file to be written. */
  public static void main(String[] args) throws IOException {
    if (args.length < 1) {
      System.err.println("Usage Zip target");
      return;
    }
    String target = args[0];
    URI uri = URI.create("jar:" + new File(target).toURI());
    Map<String, String> env = new HashMap<>();
    env.put("create", "true");
    try (FileSystem zipfs = FileSystems.newFileSystem(uri, env)) {
      Files.write(zipfs.getPath("/Main.java"), MAIN_JAVA.getBytes());
      Files.write(zipfs.getPath("/Yang.java"), YANG_JAVA.getBytes());
    }
  }

  private static String join(String... values) {
    StringBuilder out = new StringBuilder();
    int max = values.length - 1;
    for (int i = 0; i < max; i++) {
      out.append(values[i]).append('\n');
    }
    out.append(values[max]);
    return out.toString();
  }

  private static final String MAIN_JAVA = join(
    "package com.example;",
    "",
    "public class Main {",
    "",
    "  public static void main(String... args) {",
    "    Yin yin = new Yin();",
    "    Yang yang = new Yang();",
    "    yin.setYang(yang);",
    "    yang.setYin(yin);",
    "  }",
    "}");

  private static final String YANG_JAVA = join(
    "package com.example;",
    "",
    "public class Yang {",
    "",
    "  private Yin yin;",
    "",
    "  public void setYin(Yin yin) {",
    "    this.yin = yin;",
    "  }",
    "",
    "  public Yin getYin() {",
    "    return yin;",
    "  }",
    "}");
}
