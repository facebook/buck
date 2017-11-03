// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.shaking.ProguardConfigurationParser;
import com.android.tools.r8.shaking.ProguardConfigurationRule;
import com.android.tools.r8.shaking.ProguardConfigurationSource;
import com.android.tools.r8.shaking.ProguardConfigurationSourceFile;
import com.android.tools.r8.shaking.ProguardConfigurationSourceStrings;
import com.android.tools.r8.shaking.ProguardRuleParserException;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.DefaultDiagnosticsHandler;
import com.android.tools.r8.utils.InternalOptions;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class GenerateMainDexListCommand extends BaseCommand {

  private final ImmutableList<ProguardConfigurationRule> mainDexKeepRules;
  private final Path mainDexListOutput;
  private final DexItemFactory factory;

  /**
   * Get the output path for the main-dex list. Null if not set.
   */
  public Path getMainDexListOutputPath() {
    return mainDexListOutput;
  }

  public static class Builder extends BaseCommand.Builder<GenerateMainDexListCommand, Builder> {

    private final DexItemFactory factory = new DexItemFactory();
    private final List<ProguardConfigurationSource> mainDexRules = new ArrayList<>();
    private Path mainDexListOutput = null;

    @Override
    GenerateMainDexListCommand.Builder self() {
      return this;
    }

    /**
     * Add proguard configuration file resources for automatic main dex list calculation.
     */
    public GenerateMainDexListCommand.Builder addMainDexRulesFiles(Path... paths) {
      for (Path path : paths) {
        mainDexRules.add(new ProguardConfigurationSourceFile(path));
      }
      return self();
    }

    /**
     * Add proguard configuration file resources for automatic main dex list calculation.
     */
    public GenerateMainDexListCommand.Builder addMainDexRulesFiles(List<Path> paths) {
      for (Path path : paths) {
        mainDexRules.add(new ProguardConfigurationSourceFile(path));
      }
      return self();
    }

    /**
     * Add proguard configuration for automatic main dex list calculation.
     */
    public GenerateMainDexListCommand.Builder addMainDexRules(List<String> lines) {
      mainDexRules.add(new ProguardConfigurationSourceStrings(lines, Paths.get(".")));
      return self();
    }

    /**
     * Get the output path for the main-dex list. Null if not set.
     */
    public Path getMainDexListOutputPath() {
      return mainDexListOutput;
    }

    /**
     * Set the output file for the main-dex list.
     *
     * If the file exists it will be overwritten.
     */
    public GenerateMainDexListCommand.Builder setMainDexListOutputPath(Path mainDexListOutputPath) {
      mainDexListOutput = mainDexListOutputPath;
      return self();
    }


    @Override
    public GenerateMainDexListCommand build() throws CompilationException, IOException {
      // If printing versions ignore everything else.
      if (isPrintHelp() || isPrintVersion()) {
        return new GenerateMainDexListCommand(isPrintHelp(), isPrintVersion());
      }

      validate();
      ImmutableList<ProguardConfigurationRule> mainDexKeepRules;
      if (this.mainDexRules.isEmpty()) {
        mainDexKeepRules = ImmutableList.of();
      } else {
        ProguardConfigurationParser parser =
            new ProguardConfigurationParser(factory, new DefaultDiagnosticsHandler());
        try {
          parser.parse(mainDexRules);
          mainDexKeepRules = parser.getConfig().getRules();
        } catch (ProguardRuleParserException e) {
          throw new CompilationException(e.getMessage(), e.getCause());
        }
      }

      return new GenerateMainDexListCommand(
          factory,
          getAppBuilder().build(),
          mainDexKeepRules,
          mainDexListOutput);
    }
  }

  static final String USAGE_MESSAGE = String.join("\n", ImmutableList.of(
      "Usage: maindex [options] <input-files>",
      " where <input-files> are JAR files",
      " and options are:",
      "  --main-dex-rules <file>  # Proguard keep rules for classes to place in the",
      "                           # primary dex file.",
      "  --main-dex-list <file>   # List of classes to place in the primary dex file.",
      "  --main-dex-list-output <file>  # Output the full main-dex list in <file>.",
      "  --version                # Print the version.",
      "  --help                   # Print this message."));


  public static GenerateMainDexListCommand.Builder builder() {
    return new GenerateMainDexListCommand.Builder();
  }

  public static GenerateMainDexListCommand.Builder parse(String[] args)
      throws CompilationException, IOException {
    GenerateMainDexListCommand.Builder builder = builder();
    parse(args, builder);
    return builder;
  }

  private static void parse(String[] args, GenerateMainDexListCommand.Builder builder)
      throws CompilationException, IOException {
    for (int i = 0; i < args.length; i++) {
      String arg = args[i].trim();
      if (arg.length() == 0) {
        continue;
      } else if (arg.equals("--help")) {
        builder.setPrintHelp(true);
      } else if (arg.equals("--version")) {
        builder.setPrintVersion(true);
      } else if (arg.equals("--main-dex-rules")) {
        builder.addMainDexRulesFiles(Paths.get(args[++i]));
      } else if (arg.equals("--main-dex-list")) {
        builder.addMainDexListFiles(Paths.get(args[++i]));
      } else if (arg.equals("--main-dex-list-output")) {
        builder.setMainDexListOutputPath(Paths.get(args[++i]));
      } else {
        if (arg.startsWith("--")) {
          throw new CompilationException("Unknown option: " + arg);
        }
        builder.addProgramFiles(Paths.get(arg));
      }
    }
  }

  private GenerateMainDexListCommand(
      DexItemFactory factory,
      AndroidApp inputApp,
      ImmutableList<ProguardConfigurationRule> mainDexKeepRules,
      Path mainDexListOutput) {
    super(inputApp);
    this.factory = factory;
    this.mainDexKeepRules = mainDexKeepRules;
    this.mainDexListOutput = mainDexListOutput;
  }

  private GenerateMainDexListCommand(boolean printHelp, boolean printVersion) {
    super(printHelp, printVersion);
    this.factory = new DexItemFactory();
    this.mainDexKeepRules = ImmutableList.of();
    this.mainDexListOutput = null;
  }

  @Override
  InternalOptions getInternalOptions() {
    InternalOptions internal = new InternalOptions(factory);
    internal.mainDexKeepRules = mainDexKeepRules;
    if (mainDexListOutput != null) {
      internal.printMainDexListFile = mainDexListOutput;
    }
    internal.minimalMainDex = internal.debug;
    internal.removeSwitchMaps = false;
    internal.inlineAccessors = false;
    return internal;
  }
}

