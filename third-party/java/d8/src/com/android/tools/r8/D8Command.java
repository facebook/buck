// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.OutputMode;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;

/**
 * Immutable command structure for an invocation of the {@code D8} compiler.
 *
 * <p>To build a D8 command use the {@code D8Command.Builder} class. For example:
 *
 * <pre>
 *   D8Command command = D8Command.builder()
 *     .addProgramFiles(path1, path2)
 *     .setMode(CompilationMode.RELEASE)
 *     .build();
 * </pre>
 */
public class D8Command extends BaseCompilerCommand {

  /**
   * Builder for constructing a D8Command.
   */
  public static class Builder extends BaseCompilerCommand.Builder<D8Command, Builder> {

    private boolean intermediate = false;

    protected Builder(boolean ignoreDexInArchive) {
      super(CompilationMode.DEBUG, ignoreDexInArchive);
    }

    protected Builder() {
      super(CompilationMode.DEBUG);
    }

    private Builder(AndroidApp app) {
      super(CompilationMode.DEBUG, app);
    }

    /** Add classpath file resources. */
    public Builder addClasspathFiles(Path... files) throws IOException {
      getAppBuilder().addClasspathFiles(files);
      return self();
    }

    /** Add classpath file resources. */
    public Builder addClasspathFiles(Collection<Path> files) throws IOException {
      getAppBuilder().addClasspathFiles(files);
      return self();
    }

    public Builder addClasspathResourceProvider(ClassFileResourceProvider provider) {
      getAppBuilder().addClasspathResourceProvider(provider);
      return self();
    }

    public Builder setIntermediate(boolean value) {
      this.intermediate = value;
      return self();
    }

    @Override
    Builder self() {
      return this;
    }

    @Override
    protected void validate() throws CompilationException {
      super.validate();
      if (getAppBuilder().hasMainDexList() && intermediate) {
        throw new CompilationException(
            "Option --main-dex-list cannot be used with --intermediate");
      }
    }

    /**
     * Build the final D8Command.
     */
    @Override
    public D8Command build() throws CompilationException {
      if (isPrintHelp() || isPrintVersion()) {
        return new D8Command(isPrintHelp(), isPrintVersion());
      }

      validate();
      return new D8Command(
          getAppBuilder().build(),
          getOutputPath(),
          getOutputMode(),
          getMode(),
          getMinApiLevel(),
          getDiagnosticsHandler(),
          getEnableDesugaring(),
          intermediate);
    }
  }

  static final String USAGE_MESSAGE = String.join("\n", ImmutableList.of(
      "Usage: d8 [options] <input-files>",
      " where <input-files> are any combination of dex, class, zip, jar, or apk files",
      " and options are:",
      "  --debug                 # Compile with debugging information (default).",
      "  --release               # Compile without debugging information.",
      "  --output <file>         # Output result in <outfile>.",
      "                          # <file> must be an existing directory or a zip file.",
      "  --lib <file>            # Add <file> as a library resource.",
      "  --classpath <file>      # Add <file> as a classpath resource.",
      "  --min-api               # Minimum Android API level compatibility",
      "  --intermediate          # Compile an intermediate result intended for later",
      "                          # merging.",
      "  --file-per-class        # Produce a separate dex file per class",
      "  --main-dex-list <file>  # List of classes to place in the primary dex file.",
      "  --version               # Print the version of d8.",
      "  --help                  # Print this message."));

  private boolean intermediate = false;

  public static Builder builder() {
    return new Builder();
  }

  // Internal builder to start from an existing AndroidApp.
  static Builder builder(AndroidApp app) {
    return new Builder(app);
  }

  public static Builder parse(String[] args) throws CompilationException, IOException {
    CompilationMode modeSet = null;
    Path outputPath = null;
    Builder builder = builder();
    try {
      for (int i = 0; i < args.length; i++) {
        String arg = args[i].trim();
        if (arg.length() == 0) {
          continue;
        } else if (arg.equals("--help")) {
          builder.setPrintHelp(true);
        } else if (arg.equals("--version")) {
          builder.setPrintVersion(true);
        } else if (arg.equals("--debug")) {
          if (modeSet == CompilationMode.RELEASE) {
            throw new CompilationException("Cannot compile in both --debug and --release mode.");
          }
          builder.setMode(CompilationMode.DEBUG);
          modeSet = CompilationMode.DEBUG;
        } else if (arg.equals("--release")) {
          if (modeSet == CompilationMode.DEBUG) {
            throw new CompilationException("Cannot compile in both --debug and --release mode.");
          }
          builder.setMode(CompilationMode.RELEASE);
          modeSet = CompilationMode.RELEASE;
        } else if (arg.equals("--file-per-class")) {
          builder.setOutputMode(OutputMode.FilePerInputClass);
        } else if (arg.equals("--output")) {
          String output = args[++i];
          if (outputPath != null) {
            throw new CompilationException(
                "Cannot output both to '" + outputPath.toString() + "' and '" + output + "'");
          }
          outputPath = Paths.get(output);
        } else if (arg.equals("--lib")) {
          builder.addLibraryFiles(Paths.get(args[++i]));
        } else if (arg.equals("--classpath")) {
          builder.addClasspathFiles(Paths.get(args[++i]));
        } else if (arg.equals("--main-dex-list")) {
          builder.addMainDexListFiles(Paths.get(args[++i]));
        } else if (arg.equals("--min-api")) {
          builder.setMinApiLevel(Integer.valueOf(args[++i]));
        } else if (arg.equals("--intermediate")) {
          builder.setIntermediate(true);
        } else {
          if (arg.startsWith("--")) {
            throw new CompilationException("Unknown option: " + arg);
          }
          builder.addProgramFiles(Paths.get(arg));
        }
      }
      return builder.setOutputPath(outputPath);
    } catch (CompilationError e) {
      throw new CompilationException(e.getMessage(), e);
    }
  }

  private D8Command(
      AndroidApp inputApp,
      Path outputPath,
      OutputMode outputMode,
      CompilationMode mode,
      int minApiLevel,
      DiagnosticsHandler diagnosticsHandler,
      boolean enableDesugaring,
      boolean intermediate) {
    super(inputApp, outputPath, outputMode, mode, minApiLevel, diagnosticsHandler,
        enableDesugaring);
    this.intermediate = intermediate;
  }

  private D8Command(boolean printHelp, boolean printVersion) {
    super(printHelp, printVersion);
  }

  @Override
  InternalOptions getInternalOptions() {
    InternalOptions internal = new InternalOptions(new DexItemFactory());
    assert !internal.debug;
    internal.debug = getMode() == CompilationMode.DEBUG;
    internal.minimalMainDex = internal.debug;
    internal.minApiLevel = getMinApiLevel();
    internal.intermediate = intermediate;
    // Assert and fixup defaults.
    assert !internal.skipMinification;
    internal.skipMinification = true;
    assert internal.useTreeShaking;
    internal.useTreeShaking = false;

    // Disable some of R8 optimizations.
    assert internal.inlineAccessors;
    internal.inlineAccessors = false;
    assert internal.removeSwitchMaps;
    internal.removeSwitchMaps = false;
    assert internal.outline.enabled;
    internal.outline.enabled = false;
    assert internal.propagateMemberValue;
    internal.propagateMemberValue = false;

    internal.outputMode = getOutputMode();
    internal.diagnosticsHandler = getDiagnosticsHandler();
    internal.enableDesugaring = getEnableDesugaring();
    return internal;
  }
}
