// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming;

import static com.android.tools.r8.utils.DescriptorUtils.getClassBinaryNameFromDescriptor;
import static com.android.tools.r8.utils.DescriptorUtils.getDescriptorFromClassBinaryName;
import static com.android.tools.r8.utils.DescriptorUtils.getPackageBinaryNameFromJavaType;

import com.android.tools.r8.graph.DexAnnotation;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexValue;
import com.android.tools.r8.graph.DexValue.DexValueArray;
import com.android.tools.r8.graph.DexValue.DexValueString;
import com.android.tools.r8.graph.DexValue.DexValueType;
import com.android.tools.r8.naming.signature.GenericSignatureAction;
import com.android.tools.r8.naming.signature.GenericSignatureParser;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.android.tools.r8.shaking.RootSetBuilder.RootSet;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.InternalOptions.PackageObfuscationMode;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.Timing;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

class ClassNameMinifier {

  private final AppInfoWithLiveness appInfo;
  private final RootSet rootSet;
  private final PackageObfuscationMode packageObfuscationMode;
  private final boolean isAccessModificationAllowed;
  private final Set<String> noObfuscationPrefixes = Sets.newHashSet();
  private final Set<String> usedPackagePrefixes = Sets.newHashSet();
  private final Set<DexString> usedTypeNames = Sets.newIdentityHashSet();

  private final Map<DexType, DexString> renaming = Maps.newIdentityHashMap();
  private final Map<String, Namespace> states = new HashMap<>();
  private final ImmutableList<String> packageDictionary;
  private final ImmutableList<String> classDictionary;
  private final boolean keepInnerClassStructure;

  private final Namespace topLevelState;

  private GenericSignatureRewriter genericSignatureRewriter = new GenericSignatureRewriter();

  private GenericSignatureParser<DexType> genericSignatureParser =
      new GenericSignatureParser<>(genericSignatureRewriter);

  ClassNameMinifier(
      AppInfoWithLiveness appInfo,
      RootSet rootSet,
      InternalOptions options) {
    this.appInfo = appInfo;
    this.rootSet = rootSet;
    this.packageObfuscationMode = options.proguardConfiguration.getPackageObfuscationMode();
    this.isAccessModificationAllowed = options.proguardConfiguration.isAccessModificationAllowed();
    this.packageDictionary = options.proguardConfiguration.getPackageObfuscationDictionary();
    this.classDictionary = options.proguardConfiguration.getClassObfuscationDictionary();
    this.keepInnerClassStructure = options.proguardConfiguration.getKeepAttributes().signature;

    // Initialize top-level naming state.
    topLevelState = new Namespace(
        getPackageBinaryNameFromJavaType(options.proguardConfiguration.getPackagePrefix()));
    states.computeIfAbsent("", k -> topLevelState);
  }

  Map<DexType, DexString> computeRenaming(Timing timing) {
    Iterable<DexProgramClass> classes = appInfo.classes();
    // Collect names we have to keep.
    timing.begin("reserve");
    for (DexClass clazz : classes) {
      if (rootSet.noObfuscation.contains(clazz.type)) {
        assert !renaming.containsKey(clazz.type);
        registerClassAsUsed(clazz.type);
      }
    }
    timing.end();

    timing.begin("rename-classes");
    for (DexClass clazz : classes) {
      if (!renaming.containsKey(clazz.type)) {
        DexString renamed = computeName(clazz);
        renaming.put(clazz.type, renamed);
      }
    }
    timing.end();

    timing.begin("rename-dangling-types");
    for (DexClass clazz : classes) {
      renameDanglingTypes(clazz);
    }
    timing.end();

    timing.begin("rename-generic");
    renameTypesInGenericSignatures();
    timing.end();

    timing.begin("rename-arrays");
    appInfo.dexItemFactory.forAllTypes(this::renameArrayTypeIfNeeded);
    timing.end();

    return Collections.unmodifiableMap(renaming);
  }

  private void renameDanglingTypes(DexClass clazz) {
    clazz.forEachMethod(this::renameDanglingTypesInMethod);
    clazz.forEachField(this::renameDanglingTypesInField);
  }

  private void renameDanglingTypesInField(DexEncodedField field) {
    renameDanglingType(field.field.type);
  }

  private void renameDanglingTypesInMethod(DexEncodedMethod method) {
    DexProto proto = method.method.proto;
    renameDanglingType(proto.returnType);
    for (DexType type : proto.parameters.values) {
      renameDanglingType(type);
    }
  }

  private void renameDanglingType(DexType type) {
    if (appInfo.wasPruned(type)
        && !renaming.containsKey(type)
        && !rootSet.noObfuscation.contains(type)) {
      // We have a type that is defined in the program source but is only used in a proto or
      // return type. As we don't need the class, we can rename it to anything as long as it is
      // unique.
      assert appInfo.definitionFor(type) == null;
      renaming.put(type, topLevelState.nextTypeName());
    }
  }

  private void renameTypesInGenericSignatures() {
    for (DexClass clazz : appInfo.classes()) {
      rewriteGenericSignatures(clazz.annotations.annotations,
          genericSignatureParser::parseClassSignature);
      clazz.forEachField(field -> rewriteGenericSignatures(
          field.annotations.annotations, genericSignatureParser::parseFieldSignature));
      clazz.forEachMethod(method -> rewriteGenericSignatures(
          method.annotations.annotations, genericSignatureParser::parseMethodSignature));
    }
  }

  private void rewriteGenericSignatures(DexAnnotation[] annotations, Consumer<String> parser) {
    for (int i = 0; i < annotations.length; i++) {
      DexAnnotation annotation = annotations[i];
      if (DexAnnotation.isSignatureAnnotation(annotation, appInfo.dexItemFactory)) {
        parser.accept(getSignatureFromAnnotation(annotation));
        annotations[i] = DexAnnotation.createSignatureAnnotation(
            genericSignatureRewriter.getRenamedSignature(),
            appInfo.dexItemFactory);
      }
    }
  }

  private static String getSignatureFromAnnotation(DexAnnotation signatureAnnotation) {
    DexValueArray elements = (DexValueArray) signatureAnnotation.annotation.elements[0].value;
    StringBuilder signature = new StringBuilder();
    for (DexValue element : elements.getValues()) {
      signature.append(((DexValueString) element).value.toString());
    }
    return signature.toString();
  }

  /**
   * Registers the given type as used.
   * <p>
   * When {@link #keepInnerClassStructure} is true, keeping the name of an inner class will
   * automatically also keep the name of the outer class, as otherwise the structure would be
   * invalidated.
   */
  private void registerClassAsUsed(DexType type) {
    renaming.put(type, type.descriptor);
    registerPackagePrefixesAsUsed(
        getParentPackagePrefix(getClassBinaryNameFromDescriptor(type.descriptor.toSourceString())));
    usedTypeNames.add(type.descriptor);
    if (keepInnerClassStructure) {
      DexType outerClass = getOutClassForType(type);
      if (outerClass != null) {
        if (!renaming.containsKey(outerClass)) {
          // The outer class was not previously kept. We have to do this now.
          registerClassAsUsed(outerClass);
        }
      }
    }
  }

  /**
   * Registers the given package prefix and all of parent packages as used.
   */
  private void registerPackagePrefixesAsUsed(String packagePrefix) {
    // If -allowaccessmodification is not set, we may keep classes in their original packages,
    // accounting for package-private accesses.
    if (!isAccessModificationAllowed) {
      noObfuscationPrefixes.add(packagePrefix);
    }
    String usedPrefix = packagePrefix;
    while (usedPrefix.length() > 0) {
      usedPackagePrefixes.add(usedPrefix);
      usedPrefix = getParentPackagePrefix(usedPrefix);
    }
  }

  private DexType getOutClassForType(DexType type) {
    DexClass clazz = appInfo.definitionFor(type);
    if (clazz == null) {
      return null;
    }
    DexAnnotation annotation =
        clazz.annotations.getFirstMatching(appInfo.dexItemFactory.annotationEnclosingClass);
    if (annotation != null) {
      assert annotation.annotation.elements.length == 1;
      DexValue value = annotation.annotation.elements[0].value;
      return ((DexValueType) value).value;
    }
    // We do not need to preserve the names for local or anonymous classes, as they do not result
    // in a member type declaration and hence cannot be referenced as nested classes in
    // method signatures.
    // See https://docs.oracle.com/javase/specs/jls/se7/html/jls-8.html#jls-8.5.
    return null;
  }

  private DexString computeName(DexClass clazz) {
    Namespace state = null;
    if (keepInnerClassStructure) {
      // When keeping the nesting structure of inner classes, we have to insert the name
      // of the outer class for the $ prefix.
      DexType outerClass = getOutClassForType(clazz.type);
      if (outerClass != null) {
        state = getStateForOuterClass(outerClass);
      }
    }
    if (state == null) {
      state = getStateForClass(clazz);
    }
    return state.nextTypeName();
  }

  private Namespace getStateForClass(DexClass clazz) {
    String packageName = getPackageBinaryNameFromJavaType(clazz.type.getPackageDescriptor());
    // Check whether the given class should be kept.
    // or check whether the given class belongs to a package that is kept for another class.
    if (rootSet.keepPackageName.contains(clazz)
        || noObfuscationPrefixes.contains(packageName)) {
      return states.computeIfAbsent(packageName, Namespace::new);
    }
    Namespace state = topLevelState;
    switch (packageObfuscationMode) {
      case NONE:
        // For general obfuscation, rename all the involved package prefixes.
        state = getStateForPackagePrefix(packageName);
        break;
      case REPACKAGE:
        // For repackaging, all classes are repackaged to a single package.
        state = topLevelState;
        break;
      case FLATTEN:
        // For flattening, all packages are repackaged to a single package.
        state = states.computeIfAbsent(packageName, k -> {
          String renamedPackagePrefix = topLevelState.nextPackagePrefix();
          return new Namespace(renamedPackagePrefix);
        });
        break;
    }
    return state;
  }

  private Namespace getStateForPackagePrefix(String prefix) {
    Namespace state = states.get(prefix);
    if (state == null) {
      // Calculate the parent package prefix, e.g., La/b/c -> La/b
      String parentPackage = getParentPackagePrefix(prefix);
      // Create a state for parent package prefix, if necessary, in a recursive manner.
      // That recursion should end when the parent package hits the top-level, "".
      Namespace superState = getStateForPackagePrefix(parentPackage);
      // From the super state, get a renamed package prefix for the current level.
      String renamedPackagePrefix = superState.nextPackagePrefix();
      // Create a new state, which corresponds to a new name space, for the current level.
      state = new Namespace(renamedPackagePrefix);
      states.put(prefix, state);
    }
    return state;
  }

  private Namespace getStateForOuterClass(DexType outer) {
    String prefix = getClassBinaryNameFromDescriptor(outer.toDescriptorString());
    Namespace state = states.get(prefix);
    if (state == null) {
      // Create a naming state with this classes renaming as prefix.
      DexString renamed = renaming.get(outer);
      if (renamed == null) {
        // The outer class has not been renamed yet, so rename the outer class first.
        DexClass outerClass = appInfo.definitionFor(outer);
        if (outerClass == null) {
          renamed = outer.descriptor;
        } else {
          renamed = computeName(outerClass);
          renaming.put(outer, renamed);
        }
      }
      String binaryName = getClassBinaryNameFromDescriptor(renamed.toString());
      state = new Namespace(binaryName, "$");
      states.put(prefix, state);
    }
    return state;
  }

  private void renameArrayTypeIfNeeded(DexType type) {
    if (type.isArrayType()) {
      DexType base = type.toBaseType(appInfo.dexItemFactory);
      DexString value = renaming.get(base);
      if (value != null) {
        int dimensions = type.descriptor.numberOfLeadingSquareBrackets();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < dimensions; i++) {
          builder.append('[');
        }
        builder.append(value.toString());
        DexString descriptor = appInfo.dexItemFactory.createString(builder.toString());
        renaming.put(type, descriptor);
      }
    }
  }

  private class Namespace {

    private final char[] packagePrefix;
    private int typeCounter = 1;
    private int packageCounter = 1;
    private Iterator<String> packageDictionaryIterator;
    private Iterator<String> classDictionaryIterator;

    Namespace(String packageName) {
      this(packageName, "/");
    }

    Namespace(String packageName, String separator) {
      this.packagePrefix = ("L" + packageName
          // L or La/b/ (or La/b/C$)
          + (packageName.isEmpty() ? "" : separator))
          .toCharArray();
      this.packageDictionaryIterator = packageDictionary.iterator();
      this.classDictionaryIterator = classDictionary.iterator();
    }

    private String nextSuggestedNameForClass() {
      StringBuilder nextName = new StringBuilder();
      if (classDictionaryIterator.hasNext()) {
        nextName.append(packagePrefix).append(classDictionaryIterator.next()).append(';');
        return nextName.toString();
      } else {
        return StringUtils.numberToIdentifier(packagePrefix, typeCounter++, true);
      }
    }

    DexString nextTypeName() {
      DexString candidate;
      do {
        candidate = appInfo.dexItemFactory.createString(nextSuggestedNameForClass());
      } while (usedTypeNames.contains(candidate));
      usedTypeNames.add(candidate);
      return candidate;
    }

    private String nextSuggestedNameForSubpackage() {
      StringBuilder nextName = new StringBuilder();
      // Note that the differences between this method and the other variant for class renaming are
      // 1) this one uses the different dictionary and counter,
      // 2) this one does not append ';' at the end, and
      // 3) this one removes 'L' at the beginning to make the return value a binary form.
      if (packageDictionaryIterator.hasNext()) {
        nextName.append(packagePrefix).append(packageDictionaryIterator.next());
      } else {
        nextName.append(StringUtils.numberToIdentifier(packagePrefix, packageCounter++, false));
      }
      return nextName.toString().substring(1);
    }

    String nextPackagePrefix() {
      String candidate;
      do {
        candidate = nextSuggestedNameForSubpackage();
      } while (usedPackagePrefixes.contains(candidate));
      usedPackagePrefixes.add(candidate);
      return candidate;
    }

  }

  private class GenericSignatureRewriter implements GenericSignatureAction<DexType> {

    private StringBuilder renamedSignature;

    public String getRenamedSignature() {
      return renamedSignature.toString();
    }

    @Override
    public void parsedSymbol(char symbol) {
      renamedSignature.append(symbol);
    }

    @Override
    public void parsedIdentifier(String identifier) {
      renamedSignature.append(identifier);
    }

    @Override
    public DexType parsedTypeName(String name) {
      DexType type = appInfo.dexItemFactory.createType(getDescriptorFromClassBinaryName(name));
      DexString renamedDescriptor = renaming.getOrDefault(type, type.descriptor);
      renamedSignature.append(getClassBinaryNameFromDescriptor(renamedDescriptor.toString()));
      return type;
    }

    @Override
    public DexType parsedInnerTypeName(DexType enclosingType, String name) {
      assert enclosingType.isClassType();
      String enclosingDescriptor = enclosingType.toDescriptorString();
      DexType type =
          appInfo.dexItemFactory.createType(
              getDescriptorFromClassBinaryName(
                  getClassBinaryNameFromDescriptor(enclosingDescriptor)
                  + '$' + name));
      String enclosingRenamedBinaryName =
          getClassBinaryNameFromDescriptor(
              renaming.getOrDefault(enclosingType, enclosingType.descriptor).toString());
      String renamed =
          getClassBinaryNameFromDescriptor(
              renaming.getOrDefault(type, type.descriptor).toString());
      assert renamed.startsWith(enclosingRenamedBinaryName + '$');
      String outName = renamed.substring(enclosingRenamedBinaryName.length() + 1);
      renamedSignature.append(outName);
      return type;
    }

    @Override
    public void start() {
      renamedSignature = new StringBuilder();
    }

    @Override
    public void stop() {
      // nothing to do
    }
  }

  /**
   * Compute parent package prefix from the given package prefix.
   *
   * @param packagePrefix i.e. "Ljava/lang"
   * @return parent package prefix i.e. "Ljava"
   */
  static String getParentPackagePrefix(String packagePrefix) {
    int i = packagePrefix.lastIndexOf(DescriptorUtils.DESCRIPTOR_PACKAGE_SEPARATOR);
    if (i < 0) {
      return "";
    }
    return packagePrefix.substring(0, i);
  }
}
