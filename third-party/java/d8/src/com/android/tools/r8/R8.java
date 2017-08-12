// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import static com.android.tools.r8.R8Command.USAGE_MESSAGE;

import com.android.tools.r8.dex.ApplicationReader;
import com.android.tools.r8.dex.ApplicationWriter;
import com.android.tools.r8.dex.Marker;
import com.android.tools.r8.dex.Marker.Tool;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.errors.DexOverflowException;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.ClassAndMemberPublicizer;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.GraphLense;
import com.android.tools.r8.ir.conversion.IRConverter;
import com.android.tools.r8.ir.optimize.EnumOrdinalMapCollector;
import com.android.tools.r8.ir.optimize.SwitchMapCollector;
import com.android.tools.r8.jar.CfApplicationWriter;
import com.android.tools.r8.naming.Minifier;
import com.android.tools.r8.naming.NamingLens;
import com.android.tools.r8.naming.ProguardMapApplier;
import com.android.tools.r8.naming.SeedMapper;
import com.android.tools.r8.naming.SourceFileRewriter;
import com.android.tools.r8.optimize.BridgeMethodAnalysis;
import com.android.tools.r8.optimize.MemberRebindingAnalysis;
import com.android.tools.r8.optimize.VisibilityBridgeRemover;
import com.android.tools.r8.shaking.AbstractMethodRemover;
import com.android.tools.r8.shaking.AnnotationRemover;
import com.android.tools.r8.shaking.DiscardedChecker;
import com.android.tools.r8.shaking.Enqueuer;
import com.android.tools.r8.shaking.MainDexListBuilder;
import com.android.tools.r8.shaking.ProguardClassFilter;
import com.android.tools.r8.shaking.ReasonPrinter;
import com.android.tools.r8.shaking.RootSetBuilder;
import com.android.tools.r8.shaking.RootSetBuilder.RootSet;
import com.android.tools.r8.shaking.SimpleClassMerger;
import com.android.tools.r8.shaking.TreePruner;
import com.android.tools.r8.shaking.protolite.ProtoLiteExtension;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.AndroidAppOutputSink;
import com.android.tools.r8.utils.CfgPrinter;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.StringDiagnostic;
import com.android.tools.r8.utils.ThreadUtils;
import com.android.tools.r8.utils.Timing;
import com.google.common.io.ByteStreams;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class R8 {

  private final Timing timing = new Timing("R8");
  private final InternalOptions options;

  private R8(InternalOptions options) {
    this.options = options;
    options.itemFactory.resetSortedIndices();
  }

  // Compute the marker to be placed in the main dex file.
  private static Marker getMarker(InternalOptions options) {
    if (options.hasMarker()) {
      return options.getMarker();
    }
    return new Marker(Tool.R8)
        .put("version", Version.LABEL)
        .put("min-api", options.minApiLevel);
  }

  public static void writeApplication(
      ExecutorService executorService,
      DexApplication application,
      OutputSink outputSink,
      byte[] deadCode,
      NamingLens namingLens,
      byte[] proguardSeedsData,
      InternalOptions options)
      throws ExecutionException, DexOverflowException {
    try {
      Marker marker = getMarker(options);
      if (options.outputClassFiles) {
        new CfApplicationWriter(application, options).write(outputSink, executorService);
      } else {
        new ApplicationWriter(application, options, marker, deadCode, namingLens, proguardSeedsData)
            .write(outputSink, executorService);
      }
    } catch (IOException e) {
      throw new RuntimeException("Cannot write application", e);
    }
  }

  static DexApplication optimize(
      DexApplication application,
      AppInfoWithSubtyping appInfo,
      InternalOptions options)
      throws ApiLevelException, ExecutionException, IOException {
    return new R8(options).optimize(application, appInfo);
  }

  private DexApplication optimize(DexApplication application, AppInfoWithSubtyping appInfo)
      throws IOException, ApiLevelException, ExecutionException {
    return optimize(application, appInfo, GraphLense.getIdentityLense(),
        Executors.newSingleThreadExecutor());
  }

  private DexApplication optimize(
      DexApplication application,
      AppInfoWithSubtyping appInfo,
      GraphLense graphLense,
      ExecutorService executorService)
      throws IOException, ApiLevelException, ExecutionException {
    final CfgPrinter printer = options.printCfg ? new CfgPrinter() : null;

    timing.begin("Create IR");
    try {
      IRConverter converter = new IRConverter(
          appInfo, options, timing, printer, graphLense);
      application = converter.optimize(application, executorService);
    } finally {
      timing.end();
    }

    if (options.printCfg) {
      if (options.printCfgFile == null || options.printCfgFile.isEmpty()) {
        System.out.print(printer.toString());
      } else {
        try (OutputStreamWriter writer = new OutputStreamWriter(
            new FileOutputStream(options.printCfgFile),
            StandardCharsets.UTF_8)) {
          writer.write(printer.toString());
        }
      }
    }
    return application;
  }

  private Set<DexType> filterMissingClasses(Set<DexType> missingClasses,
      ProguardClassFilter dontWarnPatterns) {
    Set<DexType> result = new HashSet<>(missingClasses);
    dontWarnPatterns.filterOutMatches(result);
    return result;
  }

  static void runForTesting(AndroidApp app, OutputSink outputSink,
      InternalOptions options)
      throws IOException, CompilationException {
    ExecutorService executor = ThreadUtils.getExecutorService(options);
    try {
      runForTesting(app, outputSink, options, executor);
    } finally {
      executor.shutdown();
    }
  }

  private static void runForTesting(
      AndroidApp app,
      OutputSink outputSink, InternalOptions options,
      ExecutorService executor)
      throws IOException, CompilationException {
    new R8(options).run(app, outputSink, executor);
  }

  private void run(AndroidApp inputApp, OutputSink outputSink,
      ExecutorService executorService)
      throws IOException, CompilationException {
    if (options.quiet) {
      System.setOut(new PrintStream(ByteStreams.nullOutputStream()));
    }
    try {
      AndroidApiLevel oLevel = AndroidApiLevel.O;
      if (options.minApiLevel >= oLevel.getLevel()
          && !options.mainDexKeepRules.isEmpty()) {
        throw new CompilationError("Automatic main dex list is not supported when compiling for "
            + oLevel.getName() + " and later (--min-api " + oLevel.getLevel() + ")");
      }
      DexApplication application =
          new ApplicationReader(inputApp, options, timing).read(executorService).toDirect();

      AppInfoWithSubtyping appInfo = new AppInfoWithSubtyping(application);
      RootSet rootSet;
      byte[] proguardSeedsData = null;
      timing.begin("Strip unused code");
      try {
        Set<DexType> missingClasses = appInfo.getMissingClasses();
        missingClasses = filterMissingClasses(
            missingClasses, options.proguardConfiguration.getDontWarnPatterns());
        if (!missingClasses.isEmpty()) {
          missingClasses.forEach(
              clazz -> {
                options.diagnosticsHandler.warning(
                    new StringDiagnostic("Missing class: " + clazz.toSourceString()));
              });
          if (!options.ignoreMissingClasses) {
            throw new CompilationError(
                "Shrinking can't be performed because some library classes are missing.");
          }
        }
        rootSet =
            new RootSetBuilder(
                    application, appInfo, options.proguardConfiguration.getRules(), options)
                .run(executorService);
        Enqueuer enqueuer = new Enqueuer(appInfo, options);
        enqueuer.addExtension(new ProtoLiteExtension(appInfo));
        appInfo = enqueuer.traceApplication(rootSet, timing);
        if (options.proguardConfiguration.isPrintSeeds()) {
          ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          PrintStream out = new PrintStream(bytes);
          RootSetBuilder.writeSeeds(appInfo.withLiveness(), out);
          out.flush();
          proguardSeedsData = bytes.toByteArray();
        }
        if (options.useTreeShaking) {
          TreePruner pruner = new TreePruner(application, appInfo.withLiveness(), options);
          application = pruner.run();
          // Recompute the subtyping information.
          appInfo = appInfo.withLiveness().prunedCopyFrom(application, pruner.getRemovedClasses());
          new AbstractMethodRemover(appInfo).run();
          new AnnotationRemover(appInfo.withLiveness(), options).run();
        }
      } finally {
        timing.end();
      }

      if (options.proguardConfiguration.isAccessModificationAllowed()) {
        ClassAndMemberPublicizer.run(application);
        // We can now remove visibility bridges. Note that we do not need to update the
        // invoke-targets here, as the existing invokes will simply dispatch to the now
        // visible super-method. MemberRebinding, if run, will then dispatch it correctly.
        application = new VisibilityBridgeRemover(appInfo, application).run();
      }

      GraphLense graphLense = GraphLense.getIdentityLense();

      if (appInfo.hasLiveness()) {
        graphLense = new MemberRebindingAnalysis(appInfo.withLiveness(), graphLense).run();
        // Class merging requires inlining.
        if (!options.skipClassMerging && options.inlineAccessors) {
          timing.begin("ClassMerger");
          SimpleClassMerger classMerger = new SimpleClassMerger(application,
              appInfo.withLiveness(), graphLense, timing);
          graphLense = classMerger.run();
          timing.end();

          appInfo = appInfo.withLiveness()
              .prunedCopyFrom(application, classMerger.getRemovedClasses());
        }
        if (options.proguardConfiguration.hasApplyMappingFile()) {
          SeedMapper seedMapper = SeedMapper.seedMapperFromFile(
              options.proguardConfiguration.getApplyMappingFile());
          timing.begin("apply-mapping");
          graphLense = new ProguardMapApplier(appInfo.withLiveness(), graphLense, seedMapper)
              .run(timing);
          timing.end();
        }
        application = application.asDirect().rewrittenWithLense(graphLense);
        appInfo = appInfo.withLiveness().rewrittenWithLense(application.asDirect(), graphLense);
        // Collect switch maps and ordinals maps.
        new SwitchMapCollector(appInfo.withLiveness(), options).run();
        new EnumOrdinalMapCollector(appInfo.withLiveness(), options).run();
      }

      graphLense = new BridgeMethodAnalysis(graphLense, appInfo.withSubtyping()).run();

      application = optimize(application, appInfo, graphLense, executorService);

      // Overwrite SourceFile if specified. This step should be done after IR conversion.
      timing.begin("Rename SourceFile");
      new SourceFileRewriter(appInfo, options).run();
      timing.end();

      if (!options.mainDexKeepRules.isEmpty()) {
        appInfo = new AppInfoWithSubtyping(application);
        Enqueuer enqueuer = new Enqueuer(appInfo, options);
        // Lets find classes which may have code executed before secondary dex files installation.
        RootSet mainDexRootSet =
            new RootSetBuilder(application, appInfo, options.mainDexKeepRules, options)
                .run(executorService);
        Set<DexType> mainDexBaseClasses = enqueuer.traceMainDex(mainDexRootSet, timing);

        // Calculate the automatic main dex list according to legacy multidex constraints.
        // Add those classes to an eventual manual list of classes.
        application = application.builder()
            .addToMainDexList(new MainDexListBuilder(mainDexBaseClasses, application).run())
            .build();
      }

      appInfo = new AppInfoWithSubtyping(application);

      if (options.useTreeShaking || !options.skipMinification) {
        timing.begin("Post optimization code stripping");
        try {
          Enqueuer enqueuer = new Enqueuer(appInfo, options);
          appInfo = enqueuer.traceApplication(rootSet, timing);
          if (options.useTreeShaking) {
            TreePruner pruner = new TreePruner(application, appInfo.withLiveness(), options);
            application = pruner.run();
            appInfo = appInfo.withLiveness()
                .prunedCopyFrom(application, pruner.getRemovedClasses());
            // Print reasons on the application after pruning, so that we reflect the actual result.
            ReasonPrinter reasonPrinter = enqueuer.getReasonPrinter(rootSet.reasonAsked);
            reasonPrinter.run(application);
          }
        } finally {
          timing.end();
        }
      }

      // Only perform discard-checking if tree-shaking is turned on.
      if (options.useTreeShaking && !rootSet.checkDiscarded.isEmpty()
          && options.useDiscardedChecker) {
        new DiscardedChecker(rootSet, application, options).run();
      }

      timing.begin("Minification");
      // If we did not have keep rules, everything will be marked as keep, so no minification
      // will happen. Just avoid the overhead.
      NamingLens namingLens =
          options.skipMinification
              ? NamingLens.getIdentityLens()
              : new Minifier(appInfo.withLiveness(), rootSet, options).run(timing);
      timing.end();

      // If a method filter is present don't produce output since the application is likely partial.
      if (options.hasMethodsFilter()) {
        System.out.println("Finished compilation with method filter: ");
        options.methodsFilter.forEach((m) -> System.out.println("  - " + m));
        return;
      }

      // Generate the resulting application resources.
      writeApplication(
          executorService,
          application,
          outputSink,
          application.deadCode,
          namingLens,
          proguardSeedsData,
          options);

      options.printWarnings();
    } catch (ExecutionException e) {
      unwrapExecutionException(e);
      throw new AssertionError(e); // unwrapping method should have thrown
    } finally {
      outputSink.close();
      // Dump timings.
      if (options.printTimes) {
        timing.report();
      }
    }
  }

  static void unwrapExecutionException(ExecutionException executionException)
      throws CompilationException {
    Throwable cause = executionException.getCause();
    if (cause instanceof CompilationError) {
      // add original exception as suppressed exception to provide the original stack trace
      cause.addSuppressed(executionException);
      throw (CompilationError) cause;
    } else if (cause instanceof CompilationException) {
      cause.addSuppressed(executionException);
      throw (CompilationException) cause;
    } else if (cause instanceof RuntimeException) {
      // ForkJoinPool wraps checked exceptions in RuntimeExceptions
      if (cause.getCause() != null
          && cause.getCause() instanceof CompilationException) {
        cause.addSuppressed(executionException);
        throw (CompilationException) cause.getCause();
      // ForkJoinPool sometimes uses 2 levels of RuntimeExceptions, to provide accurate stack traces
      } else if (cause.getCause() != null && cause.getCause().getCause() != null
          && cause.getCause().getCause() instanceof CompilationException) {
        cause.addSuppressed(executionException);
        throw (CompilationException) cause.getCause().getCause();
      } else {
        cause.addSuppressed(executionException);
        throw (RuntimeException) cause;
      }
    } else {
      throw new RuntimeException(executionException.getMessage(), cause);
    }
  }

  /**
   * Main API entry for the R8 compiler.
   *
   * <p>The R8 API is intentionally limited and should "do the right thing" given a command. If this
   * API does not suffice please contact the R8 team.
   *
   * @param command R8 command.
   */
  public static void run(R8Command command) throws IOException, CompilationException {
    InternalOptions options = command.getInternalOptions();
    ExecutorService executorService = ThreadUtils.getExecutorService(options);
    try {
      run(command, executorService);
    } finally {
      executorService.shutdown();
    }
  }

  /**
   * TODO(sgjesse): Get rid of this.
   */

  public static AndroidApp runInternal(R8Command command) throws IOException, CompilationException {
    InternalOptions options = command.getInternalOptions();
    ExecutorService executorService = ThreadUtils.getExecutorService(options);
    try {
      return runInternal(command, executorService);
    } finally {
      executorService.shutdown();
    }
  }

  /**
   * Main API entry for the R8 compiler.
   *
   * <p>The R8 API is intentionally limited and should "do the right thing" given a command. If this
   * API does not suffice please contact the R8 team.
   *
   * @param command R8 command.
   * @param executor executor service from which to get threads for multi-threaded processing.
   */
  public static void run(R8Command command, ExecutorService executor)
      throws IOException, CompilationException {
    InternalOptions options = command.getInternalOptions();
    runForTesting(command.getInputApp(), command.getOutputSink(), options, executor);
  }

  /**
   * TODO(sgjesse): Get rid of this.
   */
  public static AndroidApp runInternal(R8Command command, ExecutorService executor)
      throws IOException, CompilationException {
    InternalOptions options = command.getInternalOptions();
    AndroidAppOutputSink compatSink = new AndroidAppOutputSink(command.getOutputSink());
    runForTesting(command.getInputApp(), compatSink, options, executor);
    return compatSink.build();
  }

  private static void run(String[] args) throws IOException, CompilationException {
    R8Command.Builder builder = R8Command.parse(args);
    if (builder.getOutputPath() == null) {
      builder.setOutputPath(Paths.get("."));
    }
    R8Command command = builder.build();
    if (command.isPrintHelp()) {
      System.out.println(USAGE_MESSAGE);
      return;
    }
    if (command.isPrintVersion()) {
      Version.printToolVersion("R8");
      return;
    }
    run(command);
  }

  public static void main(String[] args) {
    try {
      run(args);
    } catch (NoSuchFileException e) {
      System.err.println("File not found: " + e.getFile());
      System.exit(1);
    } catch (FileAlreadyExistsException e) {
      System.err.println("File already exists: " + e.getFile());
    } catch (IOException e) {
      System.err.println("Failed to read or write Android app: " + e.getMessage());
      System.exit(1);
    } catch (RuntimeException e) {
      System.err.println("Compilation failed with an internal error.");
      Throwable cause = e.getCause() == null ? e : e.getCause();
      cause.printStackTrace();
      System.exit(1);
    } catch (CompilationException e) {
      System.err.println("Compilation failed: " + e.getMessageForR8());
      System.exit(1);
    }
  }
}
