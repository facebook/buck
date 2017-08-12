// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.shaking.ProguardConfiguration;
import com.android.tools.r8.shaking.ProguardConfigurationParser;
import com.android.tools.r8.shaking.ProguardConfigurationRule;
import com.android.tools.r8.shaking.ProguardConfigurationSource;
import com.android.tools.r8.shaking.ProguardConfigurationSourceFile;
import com.android.tools.r8.shaking.ProguardConfigurationSourceStrings;
import com.android.tools.r8.shaking.ProguardRuleParserException;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.FileUtils;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.OutputMode;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class R8Command extends BaseCompilerCommand {

  public static class Builder extends BaseCompilerCommand.Builder<R8Command, Builder> {

    private final List<ProguardConfigurationSource> mainDexRules = new ArrayList<>();
    private Path mainDexListOutput = null;
    private Consumer<ProguardConfiguration.Builder> proguardConfigurationConsumer = null;
    private final List<ProguardConfigurationSource> proguardConfigs = new ArrayList<>();
    private Optional<Boolean> treeShaking = Optional.empty();
    private Optional<Boolean> discardedChecker = Optional.empty();
    private Optional<Boolean> minification = Optional.empty();
    private boolean ignoreMissingClassesWhenNotShrinking = false;
    private boolean ignoreMissingClasses = false;
    private boolean forceProguardCompatibility = false;
    private Path proguardMapOutput = null;

    private Builder() {
      super(CompilationMode.RELEASE);
    }

    protected Builder(boolean ignoreDexInArchive, boolean forceProguardCompatibility,
        boolean ignoreMissingClassesWhenNotShrinking, boolean ignoreMissingClasses) {
      super(CompilationMode.RELEASE, ignoreDexInArchive);
      this.forceProguardCompatibility = forceProguardCompatibility;
      this.ignoreMissingClassesWhenNotShrinking = ignoreMissingClassesWhenNotShrinking;
      this.ignoreMissingClasses = ignoreMissingClasses;
    }

    private Builder(AndroidApp app) {
      super(CompilationMode.RELEASE, app);
    }

    @Override
    Builder self() {
      return this;
    }

    /**
     * Enable/disable tree shaking. This overrides any settings in proguard configuration files.
     */
    public Builder setTreeShaking(boolean useTreeShaking) {
      treeShaking = Optional.of(useTreeShaking);
      return self();
    }

    /**
     * Enable/disable discarded checker.
     */
    public Builder setDiscardedChecker(boolean useDiscardedChecker) {
      discardedChecker = Optional.of(useDiscardedChecker);
      return self();
    }

    /**
     * Enable/disable minification. This overrides any settings in proguard configuration files.
     */
    public Builder setMinification(boolean useMinification) {
      minification = Optional.of(useMinification);
      return self();
    }

    /**
     * Add proguard configuration file resources for automatic main dex list calculation.
     */
    public Builder addMainDexRulesFiles(Path... paths) {
      for (Path path : paths) {
        mainDexRules.add(new ProguardConfigurationSourceFile(path));
      }
      return self();
    }

    /**
     * Add proguard configuration file resources for automatic main dex list calculation.
     */
    public Builder addMainDexRulesFiles(List<Path> paths) {
      for (Path path : paths) {
        mainDexRules.add(new ProguardConfigurationSourceFile(path));
      }
      return self();
    }

    /**
     * Add proguard configuration for automatic main dex list calculation.
     */
    public Builder addMainDexRules(List<String> lines) {
      mainDexRules.add(new ProguardConfigurationSourceStrings(lines, Paths.get(".")));
      return self();
    }

    public Builder setMainDexListOutputPath(Path mainDexListOutputPath) {
      mainDexListOutput = mainDexListOutputPath;
      return self();
    }

    /**
     * Add proguard configuration file resources.
     */
    public Builder addProguardConfigurationFiles(Path... paths) {
      for (Path path : paths) {
        proguardConfigs.add(new ProguardConfigurationSourceFile(path));
      }
      return self();
    }

    /**
     * Add proguard configuration file resources.
     */
    public Builder addProguardConfigurationFiles(List<Path> paths) {
      for (Path path : paths) {
        proguardConfigs.add(new ProguardConfigurationSourceFile(path));
      }
      return self();
    }

    /**
     * Add proguard configuration.
     */
    public Builder addProguardConfiguration(List<String> lines) {
      proguardConfigs.add(new ProguardConfigurationSourceStrings(lines, Paths.get(".")));
      return self();
    }

    /**
     * Add and/or chain proguard configuration consumer(s) for testing.
     */
    public Builder addProguardConfigurationConsumer(Consumer<ProguardConfiguration.Builder> c) {
      Consumer<ProguardConfiguration.Builder> oldConsumer = proguardConfigurationConsumer;
      proguardConfigurationConsumer = builder -> {
        if (oldConsumer != null) {
          oldConsumer.accept(builder);
        }
        c.accept(builder);
      };
      return self();
    }

    /**
     * Set a proguard mapping file resource.
     */
    public Builder setProguardMapFile(Path path) {
      getAppBuilder().setProguardMapFile(path);
      return self();
    }

    /**
     * Deprecated flag to avoid failing if classes are missing during compilation.
     *
     * <p>TODO: Make compilation safely assume this flag to be true and remove the flag.
     */
    Builder setIgnoreMissingClasses(boolean ignoreMissingClasses) {
      this.ignoreMissingClasses = ignoreMissingClasses;
      return self();
    }

    public Builder setProguardMapOutput(Path path) {
      this.proguardMapOutput = path;
      return self();
    }

    @Override
    protected void validate() throws CompilationException {
      super.validate();
      if (mainDexListOutput != null && mainDexRules.isEmpty() && !getAppBuilder()
          .hasMainDexList()) {
        throw new CompilationException(
            "Option --main-dex-list-output require --main-dex-rules and/or --main-dex-list");
      }
    }

    @Override
    public R8Command build() throws CompilationException, IOException {
      // If printing versions ignore everything else.
      if (isPrintHelp() || isPrintVersion()) {
        return new R8Command(isPrintHelp(), isPrintVersion());
      }

      validate();
      DexItemFactory factory = new DexItemFactory();
      ImmutableList<ProguardConfigurationRule> mainDexKeepRules;
      if (this.mainDexRules.isEmpty()) {
        mainDexKeepRules = ImmutableList.of();
      } else {
        ProguardConfigurationParser parser =
            new ProguardConfigurationParser(factory, getDiagnosticsHandler());
        try {
          parser.parse(mainDexRules);
          mainDexKeepRules = parser.getConfig().getRules();
        } catch (ProguardRuleParserException e) {
          throw new CompilationException(e.getMessage(), e.getCause());
        }
      }

      ProguardConfiguration.Builder configurationBuilder;
      if (proguardConfigs.isEmpty()) {
        configurationBuilder = ProguardConfiguration.builder(factory);
      } else {
        ProguardConfigurationParser parser =
            new ProguardConfigurationParser(factory, getDiagnosticsHandler());
        try {
          parser.parse(proguardConfigs);
        } catch (ProguardRuleParserException e) {
          throw new CompilationException(e.getMessage(), e.getCause());
        }
        configurationBuilder = parser.getConfigurationBuilder();
        configurationBuilder.setForceProguardCompatibility(forceProguardCompatibility);
      }

      if (proguardConfigurationConsumer != null) {
        proguardConfigurationConsumer.accept(configurationBuilder);
      }
      ProguardConfiguration configuration = configurationBuilder.build();
      getAppBuilder().addProgramFiles(configuration.getInjars());
      getAppBuilder().addLibraryFiles(configuration.getLibraryjars());

      boolean useTreeShaking = treeShaking.orElse(configuration.isShrinking());
      boolean useDiscardedChecker = discardedChecker.orElse(true);
      boolean useMinification = minification.orElse(configuration.isObfuscating());

      return new R8Command(
          getAppBuilder().build(),
          getOutputPath(),
          getOutputMode(),
          mainDexKeepRules,
          mainDexListOutput,
          configuration,
          getMode(),
          getMinApiLevel(),
          getDiagnosticsHandler(),
          getEnableDesugaring(),
          useTreeShaking,
          useDiscardedChecker,
          useMinification,
          ignoreMissingClasses,
          forceProguardCompatibility,
          ignoreMissingClassesWhenNotShrinking,
          proguardMapOutput);
    }
  }

  // Internal state to verify parsing properties not enforced by the builder.
  private static class ParseState {

    CompilationMode mode = null;
  }

  static final String USAGE_MESSAGE = String.join("\n", ImmutableList.of(
      "Usage: r8 [options] <input-files>",
      " where <input-files> are any combination of dex, class, zip, jar, or apk files",
      " and options are:",
      "  --release                # Compile without debugging information (default).",
      "  --debug                  # Compile with debugging information.",
      "  --output <file>          # Output result in <file>.",
      "                           # <file> must be an existing directory or a zip file.",
      "  --lib <file>             # Add <file> as a library resource.",
      "  --min-api                # Minimum Android API level compatibility.",
      "  --pg-conf <file>         # Proguard configuration <file> (implies tree",
      "                           # shaking/minification).",
      "  --pg-map-output <file>   # Output the resulting name and line mapping to <file>.",
      "  --no-tree-shaking        # Force disable tree shaking of unreachable classes.",
      "  --no-discarded-checker   # Force disable the discarded checker (when tree shaking).",
      "  --no-minification        # Force disable minification of names.",
      "  --main-dex-rules <file>  # Proguard keep rules for classes to place in the",
      "                           # primary dex file.",
      "  --main-dex-list <file>   # List of classes to place in the primary dex file.",
      "  --main-dex-list-output <file>  # Output the full main-dex list in <file>.",
      "  --version                # Print the version of r8.",
      "  --help                   # Print this message."));

  private final ImmutableList<ProguardConfigurationRule> mainDexKeepRules;
  private final Path mainDexListOutput;
  private final ProguardConfiguration proguardConfiguration;
  private final boolean useTreeShaking;
  private final boolean useDiscardedChecker;
  private final boolean useMinification;
  private final boolean ignoreMissingClasses;
  private final boolean forceProguardCompatibility;
  private final boolean ignoreMissingClassesWhenNotShrinking;
  private final Path proguardMapOutput;

  public static Builder builder() {
    return new Builder();
  }

  // Internal builder to start from an existing AndroidApp.
  static Builder builder(AndroidApp app) {
    return new Builder(app);
  }

  public static Builder parse(String[] args) throws CompilationException, IOException {
    Builder builder = builder();
    parse(args, builder, new ParseState());
    return builder;
  }

  private static ParseState parse(String[] args, Builder builder, ParseState state)
      throws CompilationException, IOException {
    for (int i = 0; i < args.length; i++) {
      String arg = args[i].trim();
      if (arg.length() == 0) {
        continue;
      } else if (arg.equals("--help")) {
        builder.setPrintHelp(true);
      } else if (arg.equals("--version")) {
        builder.setPrintVersion(true);
      } else if (arg.equals("--debug")) {
        if (state.mode == CompilationMode.RELEASE) {
          throw new CompilationException("Cannot compile in both --debug and --release mode.");
        }
        state.mode = CompilationMode.DEBUG;
        builder.setMode(state.mode);
      } else if (arg.equals("--release")) {
        if (state.mode == CompilationMode.DEBUG) {
          throw new CompilationException("Cannot compile in both --debug and --release mode.");
        }
        state.mode = CompilationMode.RELEASE;
        builder.setMode(state.mode);
      } else if (arg.equals("--output")) {
        String outputPath = args[++i];
        if (builder.getOutputPath() != null) {
          throw new CompilationException(
              "Cannot output both to '"
                  + builder.getOutputPath().toString()
                  + "' and '"
                  + outputPath
                  + "'");
        }
        builder.setOutputPath(Paths.get(outputPath));
      } else if (arg.equals("--lib")) {
        builder.addLibraryFiles(Paths.get(args[++i]));
      } else if (arg.equals("--min-api")) {
        builder.setMinApiLevel(Integer.valueOf(args[++i]));
      } else if (arg.equals("--no-tree-shaking")) {
        builder.setTreeShaking(false);
      } else if (arg.equals("--no-discarded-checker")) {
        builder.setDiscardedChecker(false);
      } else if (arg.equals("--no-minification")) {
        builder.setMinification(false);
      } else if (arg.equals("--main-dex-rules")) {
        builder.addMainDexRulesFiles(Paths.get(args[++i]));
      } else if (arg.equals("--main-dex-list")) {
        builder.addMainDexListFiles(Paths.get(args[++i]));
      } else if (arg.equals("--main-dex-list-output")) {
        builder.setMainDexListOutputPath(Paths.get(args[++i]));
      } else if (arg.equals("--pg-conf")) {
        builder.addProguardConfigurationFiles(Paths.get(args[++i]));
      } else if (arg.equals("--ignore-missing-classes")) {
        builder.setIgnoreMissingClasses(true);
      } else if (arg.equals("--pg-map-output")) {
        builder.setProguardMapOutput(Paths.get(args[++i]));
      } else if (arg.startsWith("@")) {
        // TODO(zerny): Replace this with pipe reading.
        String argsFile = arg.substring(1);
        try {
          List<String> linesInFile = FileUtils.readTextFile(Paths.get(argsFile));
          List<String> argsInFile = new ArrayList<>();
          for (String line : linesInFile) {
            for (String word : line.split("\\s")) {
              String trimmed = word.trim();
              if (!trimmed.isEmpty()) {
                argsInFile.add(trimmed);
              }
            }
          }
          // TODO(zerny): We need to define what CWD should be for files referenced in an args file.
          state = parse(argsInFile.toArray(new String[argsInFile.size()]), builder, state);
        } catch (IOException | CompilationException e) {
          throw new CompilationException(
              "Failed to read arguments from file " + argsFile + ": " + e.getMessage());
        }
      } else {
        if (arg.startsWith("--")) {
          throw new CompilationException("Unknown option: " + arg);
        }
        builder.addProgramFiles(Paths.get(arg));
      }
    }
    return state;
  }

  private R8Command(
      AndroidApp inputApp,
      Path outputPath,
      OutputMode outputMode,
      ImmutableList<ProguardConfigurationRule> mainDexKeepRules,
      Path mainDexListOutput,
      ProguardConfiguration proguardConfiguration,
      CompilationMode mode,
      int minApiLevel,
      DiagnosticsHandler diagnosticsHandler,
      boolean enableDesugaring,
      boolean useTreeShaking,
      boolean useDiscardedChecker,
      boolean useMinification,
      boolean ignoreMissingClasses,
      boolean forceProguardCompatibility,
      boolean ignoreMissingClassesWhenNotShrinking,
      Path proguardMapOutput) {
    super(inputApp, outputPath, outputMode, mode, minApiLevel, diagnosticsHandler,
        enableDesugaring);
    assert proguardConfiguration != null;
    assert mainDexKeepRules != null;
    assert getOutputMode() == OutputMode.Indexed : "Only regular mode is supported in R8";
    this.mainDexKeepRules = mainDexKeepRules;
    this.mainDexListOutput = mainDexListOutput;
    this.proguardConfiguration = proguardConfiguration;
    this.useTreeShaking = useTreeShaking;
    this.useDiscardedChecker = useDiscardedChecker;
    this.useMinification = useMinification;
    this.ignoreMissingClasses = ignoreMissingClasses;
    this.forceProguardCompatibility = forceProguardCompatibility;
    this.ignoreMissingClassesWhenNotShrinking = ignoreMissingClassesWhenNotShrinking;
    this.proguardMapOutput = proguardMapOutput;
  }

  private R8Command(boolean printHelp, boolean printVersion) {
    super(printHelp, printVersion);
    mainDexKeepRules = ImmutableList.of();
    mainDexListOutput = null;
    proguardConfiguration = null;
    useTreeShaking = false;
    useDiscardedChecker = false;
    useMinification = false;
    ignoreMissingClasses = false;
    forceProguardCompatibility = false;
    ignoreMissingClassesWhenNotShrinking = false;
    proguardMapOutput = null;
  }
  public boolean useTreeShaking() {
    return useTreeShaking;
  }

  public boolean useDiscardedChecker() {
    return useDiscardedChecker;
  }

  public boolean useMinification() {
    return useMinification;
  }

  @Override
  InternalOptions getInternalOptions() {
    InternalOptions internal = new InternalOptions(proguardConfiguration);
    assert !internal.debug;
    internal.debug = getMode() == CompilationMode.DEBUG;
    internal.minApiLevel = getMinApiLevel();
    // -dontoptimize disables optimizations by flipping related flags.
    if (!proguardConfiguration.isOptimizing()) {
      internal.skipDebugLineNumberOpt = true;
      internal.skipClassMerging = true;
      internal.inlineAccessors = false;
      internal.removeSwitchMaps = false;
      internal.outline.enabled = false;
      internal.propagateMemberValue = false;
    }
    assert !internal.skipMinification;
    internal.skipMinification = !useMinification() || !proguardConfiguration.isObfuscating();
    assert internal.useTreeShaking;
    internal.useTreeShaking = useTreeShaking();
    assert internal.useDiscardedChecker;
    internal.useDiscardedChecker = useDiscardedChecker();
    assert !internal.ignoreMissingClasses;
    internal.ignoreMissingClasses = ignoreMissingClasses;
    internal.ignoreMissingClasses |= proguardConfiguration.isIgnoreWarnings();
    internal.ignoreMissingClasses |=
        ignoreMissingClassesWhenNotShrinking && !proguardConfiguration.isShrinking();

    assert !internal.verbose;
    internal.mainDexKeepRules = mainDexKeepRules;
    internal.minimalMainDex = internal.debug;
    if (mainDexListOutput != null) {
      internal.printMainDexListFile = mainDexListOutput;
    }
    internal.outputMode = getOutputMode();
    if (internal.debug) {
      // TODO(zerny): Should we support removeSwitchMaps in debug mode? b/62936642
      internal.removeSwitchMaps = false;
      // TODO(zerny): Should we support inlining in debug mode? b/62937285
      internal.inlineAccessors = false;
    }
    internal.proguardMapOutput = proguardMapOutput;

    // EXPERIMENTAL flags.
    assert !internal.forceProguardCompatibility;
    internal.forceProguardCompatibility = forceProguardCompatibility;

    return internal;
  }
}
