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
package com.facebook.buck.android.relinker;

import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Symbols {
  public ImmutableSet<String> undefined;
  public ImmutableSet<String> global;
  public ImmutableSet<String> all;

  private Symbols(
      ImmutableSet<String> undefined,
      ImmutableSet<String> global,
      ImmutableSet<String> all) {
    this.undefined = undefined;
    this.global = global;
    this.all = all;
  }

  public static Symbols getSymbols(
      Tool objdump,
      SourcePathResolver resolver,
      Path lib) throws IOException, InterruptedException {
    ImmutableSet.Builder<String> undefined = ImmutableSet.builder();
    ImmutableSet.Builder<String> global = ImmutableSet.builder();
    ImmutableSet.Builder<String> all = ImmutableSet.builder();

    // See `man objdump`.
    Pattern re = Pattern.compile(
        "\\s*" +
            "(?<address>[0-9a-f]{8})" +
            " " +
            "(?<global>.)" +
            "(?<weak>.)" +
            "(?<constructor>.)" +
            "(?<warning>.)" +
            "(?<indirect>.)" +
            "(?<debugging>.)" +
            "(?<type>.)" +
            "\\s*" +
            "(?<section>[^\\s]*)" +
            "\\s*" +
            "(?<align>[0-9a-f]*)" +
            " " +
            "(?<name>[^\\s]*)");

    ImmutableList<String> args = ImmutableList.<String>builder()
        .addAll(objdump.getCommandPrefix(resolver))
        .add("-T")
        .add(lib.toString())
        .build();

    Process p = new ProcessBuilder(args)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start();
    BufferedReader output = new BufferedReader(new InputStreamReader(p.getInputStream()));
    String line;

    while ((line = output.readLine()) != null) {
      Matcher m = re.matcher(line);
      if (!m.matches()) {
        continue;
      }
      String symbol = m.group("name");
      if ("*UND*".equals(m.group("section"))) {
        undefined.add(symbol);
      } else if ("gu!".contains(m.group("global"))) {
        global.add(symbol);
      }
      all.add(symbol);
    }
    p.waitFor();

    if (p.exitValue() != 0) {
      throw new RuntimeException("Objdump exited with value: " + p.exitValue());
    }

    return new Symbols(undefined.build(), global.build(), all.build());
  }

}
