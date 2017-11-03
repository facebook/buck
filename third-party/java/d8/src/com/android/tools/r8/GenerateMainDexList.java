// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.dex.ApplicationReader;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.shaking.Enqueuer;
import com.android.tools.r8.shaking.MainDexListBuilder;
import com.android.tools.r8.shaking.RootSetBuilder;
import com.android.tools.r8.shaking.RootSetBuilder.RootSet;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.ThreadUtils;
import com.android.tools.r8.utils.Timing;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

public class GenerateMainDexList {
  private final Timing timing = new Timing("maindex");
  private final InternalOptions options;

  private GenerateMainDexList(InternalOptions options) {
    this.options = options;
  }

  private List<String> run(AndroidApp app) throws IOException, ExecutionException {
    ExecutorService executor = ThreadUtils.getExecutorService(options);
    DexApplication application =
        new ApplicationReader(app, options, timing).read(executor).toDirect();
    AppInfoWithSubtyping appInfo = new AppInfoWithSubtyping(application);
    RootSet mainDexRootSet =
        new RootSetBuilder(application, appInfo, options.mainDexKeepRules, options).run(executor);
    Set<DexType> mainDexBaseClasses =
        new Enqueuer(appInfo, options).traceMainDex(mainDexRootSet, timing);
    Set<DexType> mainDexClasses = new MainDexListBuilder(mainDexBaseClasses, application).run();

    List<String> result = mainDexClasses.stream()
        .map(c -> c.toSourceString().replace('.', '/') + ".class")
        .sorted()
        .collect(Collectors.toList());

    if (options.printMainDexListFile != null) {
      try (OutputStream mainDexOut = Files.newOutputStream(options.printMainDexListFile,
          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
        PrintWriter writer = new PrintWriter(mainDexOut);
        result.forEach(writer::println);
        writer.flush();
      }
    }

    return result;
  }

  /**
   * Main API entry for computing the main-dex list.
   *
   * The main-dex list is represented as a list of strings, each string specifies one class to
   * keep in the primary dex file (<code>classes.dex</code>).
   *
   * A class is specified using the following format: "com/example/MyClass.class". That is
   * "/" as separator between package components, and a trailing ".class".
   *
   * @param command main dex-list generator command.
   * @return classes to keep in the primary dex file.
   */
  public static List<String> run(GenerateMainDexListCommand command)
      throws IOException, ExecutionException {
    ExecutorService executorService = ThreadUtils.getExecutorService(command.getInternalOptions());
    try {
      return run(command, executorService);
    } finally {
      executorService.shutdown();
    }
  }

  /**
   * Main API entry for computing the main-dex list.
   *
   * The main-dex list is represented as a list of strings, each string specifies one class to
   * keep in the primary dex file (<code>classes.dex</code>).
   *
   * A class is specified using the following format: "com/example/MyClass.class". That is
   * "/" as separator between package components, and a trailing ".class".
   *
   * @param command main dex-list generator command.
   * @param executor executor service from which to get threads for multi-threaded processing.
   * @return classes to keep in the primary dex file.
   */
  public static List<String> run(GenerateMainDexListCommand command, ExecutorService executor)
      throws IOException, ExecutionException {
    AndroidApp app = command.getInputApp();
    InternalOptions options = command.getInternalOptions();
    return new GenerateMainDexList(options).run(app);
  }

  public static void main(String[] args)
      throws IOException, CompilationException, ExecutionException {
    GenerateMainDexListCommand.Builder builder = GenerateMainDexListCommand.parse(args);
    GenerateMainDexListCommand command = builder.build();
    if (command.isPrintHelp()) {
      System.out.println(GenerateMainDexListCommand.USAGE_MESSAGE);
      return;
    }
    if (command.isPrintVersion()) {
      System.out.println("MainDexListGenerator " + Version.LABEL);
      return;
    }
    List<String> result = run(command);
    if (command.getMainDexListOutputPath() == null) {
      result.forEach(System.out::println);
    }
  }
}
