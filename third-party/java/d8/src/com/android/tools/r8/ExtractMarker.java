// Copyright (c) 2017, the Rex project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.dex.ApplicationReader;
import com.android.tools.r8.dex.Marker;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.shaking.FilteredClassPath;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.Timing;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class ExtractMarker {
  private static class Command {

    public static class Builder {
      private boolean printHelp = false;
      private boolean verbose;
      private boolean summary;
      private List<Path> programFiles = new ArrayList<>();

      public Builder setPrintHelp(boolean printHelp) {
        this.printHelp = printHelp;
        return this;
      }

      public boolean isPrintHelp() {
        return printHelp;
      }

      public Builder setVerbose(boolean verbose) {
        this.verbose = verbose;
        return this;
      }

      public Builder setSummary(boolean summary) {
        this.summary = summary;
        return this;
      }

      public Builder addProgramFile(Path programFile) {
        programFiles.add(programFile);
        return this;
      }

      public ExtractMarker.Command build() throws CompilationException, IOException {
        // If printing versions ignore everything else.
        if (isPrintHelp()) {
          return new ExtractMarker.Command(isPrintHelp());
        }
        return new ExtractMarker.Command(verbose, summary, programFiles);
      }
    }

    static final String USAGE_MESSAGE = String.join("\n", ImmutableList.of(
        "Usage: extractmarker [options] <input-files>",
        " where <input-files> are dex or vdex files",
        "  --verbose               # More verbose output.",
        "  --summary               # Print summary at the end.",
        "  --help                  # Print this message."));

    public static ExtractMarker.Command.Builder builder() {
      return new Builder();
    }

    public static ExtractMarker.Command.Builder parse(String[] args)
        throws CompilationException, IOException {
      ExtractMarker.Command.Builder builder = builder();
      parse(args, builder);
      return builder;
    }

    private static void parse(String[] args, ExtractMarker.Command.Builder builder)
        throws CompilationException, IOException {
      for (int i = 0; i < args.length; i++) {
        String arg = args[i].trim();
        if (arg.length() == 0) {
          continue;
        } else if (arg.equals("--verbose")) {
          builder.setVerbose(true);
        } else if (arg.equals("--summary")) {
          builder.setSummary(true);
        } else if (arg.equals("--help")) {
          builder.setPrintHelp(true);
        } else {
          if (arg.startsWith("--")) {
            throw new CompilationException("Unknown option: " + arg);
          }
          builder.addProgramFile(Paths.get(arg));
        }
      }
    }

    private final boolean printHelp;
    private final boolean verbose;
    private final boolean summary;
    private final List<Path> programFiles;

    private Command(boolean verbose, boolean summary, List<Path> programFiles) {
      this.printHelp = false;
      this.verbose = verbose;
      this.summary = summary;
      this.programFiles = programFiles;
    }

    private Command(boolean printHelp) {
      this.printHelp = printHelp;
      this.verbose = false;
      this.summary = false;
      programFiles = ImmutableList.of();
    }

    public boolean isPrintHelp() {
      return printHelp;
    }

    public List<Path> getProgramFiles() {
      return programFiles;
    }

    public boolean getVerbose() {
      return verbose;
    }

    public boolean getSummary() {
      return summary;
    }
  }

  public static void main(String[] args)
      throws IOException, CompilationException, ExecutionException {
    ExtractMarker.Command.Builder builder = ExtractMarker.Command.parse(args);
    ExtractMarker.Command command = builder.build();
    if (command.isPrintHelp()) {
      System.out.println(ExtractMarker.Command.USAGE_MESSAGE);
      return;
    }

    // Dex code is not needed for getting the marker. VDex files typically contains quickened byte
    // codes which cannot be read, and we want to get the marker from vdex files as well.
    int d8Count = 0;
    int r8Count = 0;
    int otherCount = 0;
    for (Path programFile : command.getProgramFiles()) {
      try {
        InternalOptions options = new InternalOptions();
        options.skipReadingDexCode = true;
        options.minApiLevel = AndroidApiLevel.P.getLevel();
        AndroidApp.Builder appBuilder = AndroidApp.builder();
        appBuilder.setVdexAllowed();
        appBuilder.addProgramFiles(FilteredClassPath.unfiltered(programFile));
        DexApplication dexApp =
            new ApplicationReader(appBuilder.build(), options, new Timing("ExtractMarker"))
                .read();
        Marker readMarker = dexApp.dexItemFactory.extractMarker();
        if (command.getVerbose()) {
          System.out.print(programFile);
          System.out.print(": ");
        }
        if (readMarker == null) {
          System.out.println("D8/R8 marker not found.");
          otherCount++;
        } else {
          System.out.println(readMarker.toString());
          if (readMarker.isD8()) {
            d8Count++;
          } else {
            r8Count++;
          }
        }
      } catch (CompilationError e) {
        System.out.println(
            "Failed to read dex/vdex file `" + programFile +"`: '" + e.getMessage() + "'");
      }
    }
    if (command.getSummary()) {
      System.out.println("D8: " + d8Count);
      System.out.println("R8: " + r8Count);
      System.out.println("Other: " + otherCount);
      System.out.println("Total: " + (d8Count + r8Count + otherCount));
    }
  }
}
