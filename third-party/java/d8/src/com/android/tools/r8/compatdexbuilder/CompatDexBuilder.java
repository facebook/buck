// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.compatdexbuilder;

import com.android.tools.r8.CompilationException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Output;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.ThreadUtils;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class CompatDexBuilder {

  private String input = null;
  private String output = null;
  private int numberOfThreads = 8;
  private boolean noLocals = false;

  public static void main(String[] args)
      throws IOException, InterruptedException, ExecutionException {
    new CompatDexBuilder().run(args);
  }

  private void run(String[] args) throws IOException, InterruptedException, ExecutionException {
    System.out.println("CompatDexBuilder " + String.join(" ", args));

    List<String> flags = new ArrayList<>();

    for (String arg : args) {
      if (arg.startsWith("@")) {
        flags.addAll(Files.readAllLines(Paths.get(arg.substring(1))));
      } else {
        flags.add(arg);
      }
    }

    for (int i = 0; i < flags.size(); i++) {
      String flag = flags.get(i);
      if (flag.startsWith("--positions")) {
        continue;
      }
      if (flag.startsWith("--num-threads=")) {
        numberOfThreads = Integer.parseInt(flag.substring("--num-threads=".length()));
      }
      switch (flag) {
        case "--input_jar":
          input = flags.get(++i);
          break;
        case "--output_zip":
          output = flags.get(++i);
          break;
        case "--verify-dex-file":
        case "--no-verify-dex-file":
        case "--show_flags":
        case "--no-optimize":
        case "--nooptimize":
        case "--help":
          // Ignore
          break;
        case "--nolocals":
          noLocals = true;
          break;
        default:
          System.err.println("Unsupported option: " + flag);
          System.exit(1);
      }
    }

    if (input == null) {
      System.err.println("No input jar specified");
      System.exit(1);
    }

    if (output == null) {
      System.err.println("No output jar specified");
      System.exit(1);
    }

    ExecutorService executor = ThreadUtils.getExecutorService(numberOfThreads);
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(Paths.get(output)))) {

      List<ZipEntry> toDex = new ArrayList<>();

      try (ZipFile zipFile = new ZipFile(input)) {
        final Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
          ZipEntry entry = entries.nextElement();
          if (!entry.getName().endsWith(".class")) {
            try (InputStream stream = zipFile.getInputStream(entry)) {
              addEntry(entry.getName(), stream, entry.getTime(), out);
            }
          } else {
            toDex.add(entry);
          }
        }

        List<Future<D8Output>> futures = new ArrayList<>(toDex.size());
        for (int i = 0; i < toDex.size(); i++) {
          ZipEntry classEntry = toDex.get(i);
          futures.add(executor.submit(() -> dexEntry(zipFile, classEntry, executor)));
        }
        for (int i = 0; i < futures.size(); i++) {
          D8Output result = futures.get(i).get();
          ZipEntry entry = toDex.get(i);
          assert result.getDexResources().size() == 1;
          try (InputStream dexStream = result.getDexResources().get(0).getStream()) {
            addEntry(entry.getName() + ".dex", dexStream, entry.getTime(), out);
          }
        }
      }
    } finally {
      executor.shutdown();
    }
  }

  private D8Output dexEntry(ZipFile zipFile, ZipEntry classEntry, ExecutorService executor)
      throws IOException, CompilationException {
    try (InputStream stream = zipFile.getInputStream(classEntry)) {
      CompatDexBuilderCommandBuilder builder = new CompatDexBuilderCommandBuilder();
      builder
          .addClassProgramData(ByteStreams.toByteArray(stream))
          .setMode(noLocals ? CompilationMode.RELEASE : CompilationMode.DEBUG)
          .setMinApiLevel(AndroidApiLevel.H_MR2.getLevel());
      return D8.run(builder.build(), executor);
    }
  }

  private static void addEntry(String name, InputStream in, long time, ZipOutputStream out)
      throws IOException {
    ZipEntry zipEntry = new ZipEntry(name);
    byte[] bytes = ByteStreams.toByteArray(in);
    CRC32 crc32 = new CRC32();
    crc32.update(bytes);
    zipEntry.setSize(bytes.length);
    zipEntry.setMethod(ZipEntry.STORED);
    zipEntry.setCrc(crc32.getValue());
    zipEntry.setTime(time);
    out.putNextEntry(zipEntry);
    out.write(bytes);
    out.closeEntry();
  }
}
