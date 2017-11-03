// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.dex.ApplicationReader;
import com.android.tools.r8.graph.AssemblyWriter;
import com.android.tools.r8.graph.DexByteCodeWriter;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.SmaliWriter;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.ThreadUtils;
import com.android.tools.r8.utils.Timing;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

public class Disassemble {
  public static class DisassembleCommand extends BaseCommand {

    private final Path outputPath;

    public static class Builder
        extends BaseCommand.Builder<DisassembleCommand, DisassembleCommand.Builder> {

      private Path outputPath = null;
      private boolean useSmali = false;

      @Override
      DisassembleCommand.Builder self() {
        return this;
      }

      public DisassembleCommand.Builder setProguardMapFile(Path path) {
        getAppBuilder().setProguardMapFile(path);
        return this;
      }

      public Path getOutputPath() {
        return outputPath;
      }

      public DisassembleCommand.Builder setOutputPath(Path outputPath) {
        this.outputPath = outputPath;
        return this;
      }

      public DisassembleCommand.Builder setUseSmali(boolean useSmali) {
        this.useSmali = useSmali;
        return this;
      }

      @Override
      public DisassembleCommand build() throws CompilationException, IOException {
        // If printing versions ignore everything else.
        if (isPrintHelp() || isPrintVersion()) {
          return new DisassembleCommand(isPrintHelp(), isPrintVersion());
        }

        validate();
        return new DisassembleCommand(getAppBuilder().build(), getOutputPath(), useSmali);
      }
    }

    static final String USAGE_MESSAGE = String.join("\n", ImmutableList.of(
        "Usage: disasm [options] <input-files>",
        " where <input-files> are dex files",
        " and options are:",
        "  --smali                 # Disassemble using smali syntax.",
        "  --pg-map <file>         # Proguard map <file> for mapping names.",
        "  --output                # Specify a file or directory to write to.",
        "  --version               # Print the version of r8.",
        "  --help                  # Print this message."));


    private final boolean useSmali;

    public static DisassembleCommand.Builder builder() {
      return new DisassembleCommand.Builder();
    }

    public static DisassembleCommand.Builder parse(String[] args)
        throws CompilationException, IOException {
      DisassembleCommand.Builder builder = builder();
      parse(args, builder);
      return builder;
    }

    private static void parse(String[] args, DisassembleCommand.Builder builder)
        throws CompilationException, IOException {
      for (int i = 0; i < args.length; i++) {
        String arg = args[i].trim();
        if (arg.length() == 0) {
          continue;
        } else if (arg.equals("--help")) {
          builder.setPrintHelp(true);
        } else if (arg.equals("--version")) {
          builder.setPrintVersion(true);
        } else if (arg.equals("--smali")) {
          builder.setUseSmali(true);
        } else if (arg.equals("--pg-map")) {
          builder.setProguardMapFile(Paths.get(args[++i]));
        } else if (arg.equals("--output")) {
          String outputPath = args[++i];
          builder.setOutputPath(Paths.get(outputPath));
        } else {
          if (arg.startsWith("--")) {
            throw new CompilationException("Unknown option: " + arg);
          }
          builder.addProgramFiles(Paths.get(arg));
        }
      }
    }

    private DisassembleCommand(AndroidApp inputApp, Path outputPath, boolean useSmali) {
      super(inputApp);
      this.outputPath = outputPath;
      this.useSmali = useSmali;
    }

    private DisassembleCommand(boolean printHelp, boolean printVersion) {
      super(printHelp, printVersion);
      this.outputPath = null;
      this.useSmali = false;
    }

    public Path getOutputPath() {
      return outputPath;
    }

    public boolean useSmali() {
      return useSmali;
    }

    @Override
    InternalOptions getInternalOptions() {
      InternalOptions internal = new InternalOptions();
      internal.useSmaliSyntax = useSmali;
      return internal;
    }
  }

  public static void main(String[] args)
      throws IOException, CompilationException, ExecutionException {
    DisassembleCommand.Builder builder = DisassembleCommand.parse(args);
    DisassembleCommand command = builder.build();
    if (command.isPrintHelp()) {
      System.out.println(DisassembleCommand.USAGE_MESSAGE);
      return;
    }
    if (command.isPrintVersion()) {
      System.out.println("Disassemble (R8) " + Version.LABEL);
      return;
    }
    disassemble(command);
  }

  public static void disassemble(DisassembleCommand command)
      throws IOException, ExecutionException {
    AndroidApp app = command.getInputApp();
    InternalOptions options = command.getInternalOptions();
    ExecutorService executor = ThreadUtils.getExecutorService(options);
    Timing timing = new Timing("disassemble");
    try {
      DexApplication application = new ApplicationReader(app, options, timing).read(executor);
      DexByteCodeWriter writer = command.useSmali()
          ? new SmaliWriter(application, options)
          : new AssemblyWriter(application, options);
      if (command.getOutputPath() != null) {
        writer.write(command.getOutputPath());
      } else {
        writer.write(System.out);
      }
    } finally {
      executor.shutdown();
    }
  }
}
