// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.shaking.FilteredClassPath;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.ListUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Base class for commands and command builders for applications/tools which take an Android
 * application (and a main-dex list) as input.
 */
abstract class BaseCommand {

  private final boolean printHelp;
  private final boolean printVersion;

  private final AndroidApp app;

  BaseCommand(boolean printHelp, boolean printVersion) {
    this.printHelp = printHelp;
    this.printVersion = printVersion;
    // All other fields are initialized with stub/invalid values.
    this.app = null;
  }

  BaseCommand(AndroidApp app) {
    assert app != null;
    this.app = app;
    // Print options are not set.
    printHelp = false;
    printVersion = false;
  }

  public boolean isPrintHelp() {
    return printHelp;
  }

  public boolean isPrintVersion() {
    return printVersion;
  }

  // Internal access to the input resources.
  AndroidApp getInputApp() {
    return app;
  }

  // Internal access to the internal options.
  abstract InternalOptions getInternalOptions();

  abstract public static class Builder<C extends BaseCommand, B extends Builder<C, B>> {

    private boolean printHelp = false;
    private boolean printVersion = false;
    private final AndroidApp.Builder app;

    protected List<Path> programFiles = new ArrayList<>();

    protected Builder() {
      this(AndroidApp.builder(), false);
    }

    protected Builder(AndroidApp.Builder builder, boolean ignoreDexInArchive) {
      this.app = builder;
      app.setIgnoreDexInArchive(ignoreDexInArchive);
    }

    abstract B self();

    public abstract C build() throws CompilationException, IOException;

    // Internal accessor for the application resources.
    AndroidApp.Builder getAppBuilder() {
      return app;
    }

    /**
     * This is not part of the public API of a command, but allows subclasses to add vdex
     * files as allowed input.
     *
     * Default is that vdex is not allowed.
     */
    B setVdexAllowed() {
      getAppBuilder().setVdexAllowed();
      return self();
    }

    /** Add program file resources. */
    public B addProgramFiles(Path... files) throws IOException {
      addProgramFiles(Arrays.asList(files));
      return self();
    }

    /** Add program file resources. */
    public B addProgramFiles(Collection<Path> files) throws IOException {
      app.addProgramFiles(ListUtils.map(files, FilteredClassPath::unfiltered));
      programFiles.addAll(files);
      return self();
    }

    /** Add library file resource provider. */
    public B addLibraryResourceProvider(ClassFileResourceProvider provider) {
      getAppBuilder().addLibraryResourceProvider(provider);
      return self();
    }

    /** Add library file resources. */
    public B addLibraryFiles(Path... files) throws IOException {
      addLibraryFiles(Arrays.asList(files));
      return self();
    }

    /** Add library file resources. */
    public B addLibraryFiles(Collection<Path> files) throws IOException {
      app.addLibraryFiles(ListUtils.map(files, FilteredClassPath::unfiltered));
      return self();
    }

    /** Add Java-bytecode program-data. */
    public B addClassProgramData(byte[]... data) {
      app.addClassProgramData(data);
      return self();
    }

    /** Add Java-bytecode program-data. */
    public B addClassProgramData(Collection<byte[]> data) {
      app.addClassProgramData(data);
      return self();
    }

    /** Add dex program-data. */
    public B addDexProgramData(byte[]... data) {
      app.addDexProgramData(data);
      return self();
    }

    /** Add dex program-data. */
    public B addDexProgramData(Collection<byte[]> data) {
      app.addDexProgramData(data);
      return self();
    }

    /**
     * Add main-dex list files.
     *
     * Each line in each of the files specifies one class to keep in the primary dex file
     * (<code>classes.dex</code>).
     *
     * A class is specified using the following format: "com/example/MyClass.class". That is
     * "/" as separator between package components, and a trailing ".class".
     */
    public B addMainDexListFiles(Path... files) throws IOException {
      app.addMainDexListFiles(files);
      return self();
    }

    /**
     * Add main-dex list files.
     *
     * @see #addMainDexListFiles(Path...)
     */
    public B addMainDexListFiles(Collection<Path> files) throws IOException {
      app.addMainDexListFiles(files);
      return self();
    }

    /**
     * Add main-dex classes.
     *
     * Add classes to keep in the primary dex file (<code>classes.dex</code>).
     *
     * NOTE: The name of the classes is specified using the Java fully qualified names format
     * (e.g. "com.example.MyClass"), and <i>not</i> the format used by the main-dex list file.
     */
    public B addMainDexClasses(String... classes) {
      app.addMainDexClasses(classes);
      return self();
    }

    /**
     * Add main-dex classes.
     *
     * Add classes to keep in the primary dex file (<code>classes.dex</code>).
     *
     * NOTE: The name of the classes is specified using the Java fully qualified names format
     * (e.g. "com.example.MyClass"), and <i>not</i> the format used by the main-dex list file.
     */
    public B addMainDexClasses(Collection<String> classes) {
      app.addMainDexClasses(classes);
      return self();
    }

    /** True if the print-help flag is enabled. */
    public boolean isPrintHelp() {
      return printHelp;
    }

    /** Set the value of the print-help flag. */
    public B setPrintHelp(boolean printHelp) {
      this.printHelp = printHelp;
      return self();
    }

    /** True if the print-version flag is enabled. */
    public boolean isPrintVersion() {
      return printVersion;
    }

    /** Set the value of the print-version flag. */
    public B setPrintVersion(boolean printVersion) {
      this.printVersion = printVersion;
      return self();
    }

    protected void validate() throws CompilationException {
      // Currently does nothing.
    }
  }
}
