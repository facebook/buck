// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.Resource.Origin;
import com.android.tools.r8.dex.Marker;
import com.android.tools.r8.errors.InvalidDebugInfoException;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.shaking.ProguardConfiguration;
import com.android.tools.r8.shaking.ProguardConfigurationRule;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

public class InternalOptions {

  public final DexItemFactory itemFactory;
  public final ProguardConfiguration proguardConfiguration;

  // Constructor for testing and/or other utilities.
  public InternalOptions() {
    itemFactory = new DexItemFactory();
    proguardConfiguration = ProguardConfiguration.defaultConfiguration(itemFactory);
  }

  // Constructor for D8.
  public InternalOptions(DexItemFactory factory) {
    assert factory != null;
    itemFactory = factory;
    proguardConfiguration = ProguardConfiguration.defaultConfiguration(itemFactory);
  }

  // Constructor for R8.
  public InternalOptions(ProguardConfiguration proguardConfiguration) {
    assert proguardConfiguration != null;
    this.proguardConfiguration = proguardConfiguration;
    itemFactory = proguardConfiguration.getDexItemFactory();
  }

  public final int NOT_SPECIFIED = -1;

  public boolean printTimes = false;

  public boolean outputClassFiles = false;

  // Optimization-related flags. These should conform to -dontoptimize.
  public boolean skipDebugLineNumberOpt = false;
  public boolean skipClassMerging = true;
  public boolean inlineAccessors = true;
  public boolean removeSwitchMaps = true;
  public final OutlineOptions outline = new OutlineOptions();
  public boolean propagateMemberValue = true;

  // Number of threads to use while processing the dex files.
  public int numberOfThreads = NOT_SPECIFIED;
  // Print smali disassembly.
  public boolean useSmaliSyntax = false;
  // Verbose output.
  public boolean verbose = false;
  // Silencing output.
  public boolean quiet = false;

  // Hidden marker for classes.dex
  private boolean hasMarker = false;
  private Marker marker;

  public boolean hasMarker() {
    return hasMarker;
  }

  public void setMarker(Marker marker) {
    this.hasMarker = true;
    this.marker = marker;
  }

  public Marker getMarker() {
    assert hasMarker();
    return marker;
  }

  public List<String> methodsFilter = ImmutableList.of();
  public int minApiLevel = AndroidApiLevel.getDefault().getLevel();
  // Skipping min_api check and compiling an intermediate result intended for later merging.
  public boolean intermediate = false;
  public List<String> logArgumentsFilter = ImmutableList.of();

  // Flag to turn on/off desugaring in D8/R8.
  public boolean enableDesugaring = true;
  // Defines interface method rewriter behavior.
  public OffOrAuto interfaceMethodDesugaring = OffOrAuto.Auto;
  // Defines try-with-resources rewriter behavior.
  public OffOrAuto tryWithResourcesDesugaring = OffOrAuto.Auto;

  // Application writing mode.
  public OutputMode outputMode = OutputMode.Indexed;

  public boolean useTreeShaking = true;
  public boolean useDiscardedChecker = true;

  public boolean printCfg = false;
  public String printCfgFile;
  public Path printMainDexListFile;
  public boolean ignoreMissingClasses = false;
  // EXPERIMENTAL flag to get behaviour as close to Proguard as possible.
  public boolean forceProguardCompatibility = false;
  public boolean skipMinification = false;
  public boolean disableAssertions = true;
  public boolean debugKeepRules = false;
  public boolean allowParameterName = false;

  public boolean debug = false;
  public final TestingOptions testing = new TestingOptions();

  public ImmutableList<ProguardConfigurationRule> mainDexKeepRules = ImmutableList.of();
  public boolean minimalMainDex;

  public static class InvalidParameterAnnotationInfo {

    final DexMethod method;
    final int expectedParameterCount;
    final int actualParameterCount;

    public InvalidParameterAnnotationInfo(
        DexMethod method, int expectedParameterCount, int actualParameterCount) {
      this.method = method;
      this.expectedParameterCount = expectedParameterCount;
      this.actualParameterCount = actualParameterCount;
    }
  }

  public boolean warningMissingEnclosingMember = false;

  private Map<Origin, List<InvalidParameterAnnotationInfo>> warningInvalidParameterAnnotations;

  private Map<Origin, List<DexEncodedMethod>> warningInvalidDebugInfo;

  // Don't read code from dex files. Used to extract non-code information from vdex files where
  // the code contains unsupported byte codes.
  public boolean skipReadingDexCode = false;

  public Path proguardMapOutput = null;

  public DiagnosticsHandler diagnosticsHandler = new DefaultDiagnosticsHandler();

  public void warningInvalidParameterAnnotations(
      DexMethod method, Origin origin, int expected, int actual) {
    if (warningInvalidParameterAnnotations == null) {
      warningInvalidParameterAnnotations = new HashMap<>();
    }
    warningInvalidParameterAnnotations
        .computeIfAbsent(origin, k -> new ArrayList<>())
        .add(new InvalidParameterAnnotationInfo(method, expected, actual));
  }

  public void warningInvalidDebugInfo(
      DexEncodedMethod method, Origin origin, InvalidDebugInfoException e) {
    if (warningInvalidDebugInfo == null) {
      warningInvalidDebugInfo = new HashMap<>();
    }
    warningInvalidDebugInfo.computeIfAbsent(origin, k -> new ArrayList<>()).add(method);
  }

  public boolean printWarnings() {
    boolean printed = false;
    boolean printOutdatedToolchain = false;
    if (warningInvalidParameterAnnotations != null) {
      // TODO(b/67626202): Add a regression test with a program that hits this issue.
      diagnosticsHandler.warning(
          new StringDiagnostic(
              "Invalid parameter counts in MethodParameter attributes. "
                  + "This is likely due to Proguard having removed a parameter."));
      for (Origin origin : new TreeSet<>(warningInvalidParameterAnnotations.keySet())) {
        StringBuilder builder =
            new StringBuilder("Methods with invalid MethodParameter attributes:");
        for (InvalidParameterAnnotationInfo info : warningInvalidParameterAnnotations.get(origin)) {
          builder
              .append("\n  ")
              .append(info.method)
              .append(" expected count: ")
              .append(info.expectedParameterCount)
              .append(" actual count: ")
              .append(info.actualParameterCount);
        }
        diagnosticsHandler.info(new StringDiagnostic(builder.toString(), origin));
      }
      printed = true;
    }
    if (warningInvalidDebugInfo != null) {
      int count = 0;
      for (List<DexEncodedMethod> methods : warningInvalidDebugInfo.values()) {
        count += methods.size();
      }
      diagnosticsHandler.warning(
          new StringDiagnostic(
              "Stripped invalid locals information from "
                  + count
                  + (count == 1 ? " method." : " methods.")));
      for (Origin origin : new TreeSet<>(warningInvalidDebugInfo.keySet())) {
        StringBuilder builder = new StringBuilder("Methods with invalid locals information:");
        for (DexEncodedMethod method : warningInvalidDebugInfo.get(origin)) {
          builder.append("\n  ").append(method.toSourceString());
        }
        diagnosticsHandler.info(new StringDiagnostic(builder.toString(), origin));
      }
      printed = true;
      printOutdatedToolchain = true;
    }
    if (warningMissingEnclosingMember) {
      diagnosticsHandler.warning(
          new StringDiagnostic(
              "InnerClass annotations are missing corresponding EnclosingMember annotations."
                  + " Such InnerClass annotations are ignored."));
      printed = true;
      printOutdatedToolchain = true;
    }
    if (printOutdatedToolchain) {
      diagnosticsHandler.info(
          new StringDiagnostic(
              "Some warnings are typically a sign of using an outdated Java toolchain."
                  + " To fix, recompile the source with an updated toolchain."));
    }
    return printed;
  }

  public boolean hasMethodsFilter() {
    return methodsFilter.size() > 0;
  }

  public boolean methodMatchesFilter(DexEncodedMethod method) {
    // Not specifying a filter matches all methods.
    if (!hasMethodsFilter()) {
      return true;
    }
    // Currently the filter is simple string equality on the qualified name.
    String qualifiedName = method.qualifiedName();
    return methodsFilter.indexOf(qualifiedName) >= 0;
  }

  public boolean methodMatchesLogArgumentsFilter(DexEncodedMethod method) {
    // Not specifying a filter matches no methods.
    if (logArgumentsFilter.size() == 0) {
      return false;
    }
    // Currently the filter is simple string equality on the qualified name.
    String qualifiedName = method.qualifiedName();
    return logArgumentsFilter.indexOf(qualifiedName) >= 0;
  }

  public enum PackageObfuscationMode {
    // General package obfuscation.
    NONE,
    // Repackaging all classes into the single user-given (or top-level) package.
    REPACKAGE,
    // Repackaging all packages into the single user-given (or top-level) package.
    FLATTEN
  }

  public static class OutlineOptions {

    public boolean enabled = true;
    public static final String className = "r8.GeneratedOutlineSupport";
    public String methodPrefix = "outline";
    public int minSize = 3;
    public int maxSize = 99;
    public int threshold = 20;
  }

  public static class TestingOptions {

    public Function<Set<DexEncodedMethod>, Set<DexEncodedMethod>> irOrdering =
        Function.identity();

    public boolean invertConditionals = false;
  }

  public boolean canUseInvokePolymorphicOnVarHandle() {
    return minApiLevel >= AndroidApiLevel.P.getLevel();
  }

  public boolean canUseInvokePolymorphic() {
    return minApiLevel >= AndroidApiLevel.O.getLevel();
  }

  public boolean canUseConstantMethodHandle() {
    return minApiLevel >= AndroidApiLevel.P.getLevel();
  }

  public boolean canUseConstantMethodType() {
    return minApiLevel >= AndroidApiLevel.P.getLevel();
  }

  public boolean canUseInvokeCustom() {
    return minApiLevel >= AndroidApiLevel.O.getLevel();
  }

  public boolean canUseDefaultAndStaticInterfaceMethods() {
    return minApiLevel >= AndroidApiLevel.N.getLevel();
  }

  public boolean canUsePrivateInterfaceMethods() {
    return minApiLevel >= AndroidApiLevel.N.getLevel();
  }

  public boolean canUseMultidex() {
    return intermediate || minApiLevel >= AndroidApiLevel.L.getLevel();
  }

  public boolean canUseLongCompareAndObjectsNonNull() {
    return minApiLevel >= AndroidApiLevel.K.getLevel();
  }

  public boolean canUseSuppressedExceptions() {
    return minApiLevel >= AndroidApiLevel.K.getLevel();
  }

  // APIs for accessing parameter names annotations are not available before Android O, thus does
  // not emit them to avoid wasting space in Dex files because runtimes before Android O will ignore
  // them.
  public boolean canUseParameterNameAnnotations() {
    return minApiLevel >= AndroidApiLevel.O.getLevel();
  }

  // Dalvik x86-atom backend had a bug that made it crash on filled-new-array instructions for
  // arrays of objects. This is unfortunate, since this never hits arm devices, but we have
  // to disallow filled-new-array of objects for dalvik until kitkat. The buggy code was
  // removed during the jelly-bean release cycle and is not there from kitkat.
  //
  // Buggy code that accidentally call code that only works on primitives arrays.
  //
  // https://android.googlesource.com/platform/dalvik/+/ics-mr0/vm/mterp/out/InterpAsm-x86-atom.S#25106
  public boolean canUseFilledNewArrayOfObjects() {
    return minApiLevel >= AndroidApiLevel.K.getLevel();
  }
}
