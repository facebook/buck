// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.DexAnnotation;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItem;
import com.android.tools.r8.graph.DexLibraryClass;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DirectMappedDexApplication;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.MethodSignatureEquivalence;
import com.android.tools.r8.utils.StringDiagnostic;
import com.android.tools.r8.utils.ThreadUtils;
import com.google.common.base.Equivalence.Wrapper;
import com.google.common.collect.Sets;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class RootSetBuilder {

  private DirectMappedDexApplication application;
  private final AppInfo appInfo;
  private final List<ProguardConfigurationRule> rules;
  private final Map<DexItem, ProguardKeepRule> noShrinking = new IdentityHashMap<>();
  private final Set<DexItem> noOptimization = Sets.newIdentityHashSet();
  private final Set<DexItem> noObfuscation = Sets.newIdentityHashSet();
  private final Set<DexItem> reasonAsked = Sets.newIdentityHashSet();
  private final Set<DexItem> keepPackageName = Sets.newIdentityHashSet();
  private final Set<ProguardConfigurationRule> rulesThatUseExtendsOrImplementsWrong =
      Sets.newIdentityHashSet();
  private final Set<DexItem> checkDiscarded = Sets.newIdentityHashSet();
  private final Set<DexItem> alwaysInline = Sets.newIdentityHashSet();
  private final Map<DexItem, Map<DexItem, ProguardKeepRule>> dependentNoShrinking =
      new IdentityHashMap<>();
  private final Map<DexItem, ProguardMemberRule> noSideEffects = new IdentityHashMap<>();
  private final Map<DexItem, ProguardMemberRule> assumedValues = new IdentityHashMap<>();
  private final InternalOptions options;

  public RootSetBuilder(DexApplication application, AppInfo appInfo,
      List<ProguardConfigurationRule> rules, InternalOptions options) {
    this.application = application.asDirect();
    this.appInfo = appInfo;
    this.rules = rules;
    this.options = options;
  }

  private boolean anySuperTypeMatches(DexType type, ProguardTypeMatcher name,
      ProguardTypeMatcher annotation) {
    while (type != null) {
      DexClass clazz = application.definitionFor(type);
      if (clazz == null) {
        // TODO(herhut): Warn about broken supertype chain?
        return false;
      }
      if (name.matches(clazz.type) && containsAnnotation(annotation, clazz.annotations)) {
        return true;
      }
      type = clazz.superType;
    }
    return false;
  }

  private boolean anyImplementedInterfaceMatches(DexClass clazz,
      ProguardTypeMatcher className, ProguardTypeMatcher annotation) {
    if (clazz == null) {
      return false;
    }
    for (DexType iface : clazz.interfaces.values) {
      DexClass ifaceClass = application.definitionFor(iface);
      if (ifaceClass == null) {
        // TODO(herhut): Warn about broken supertype chain?
        return false;
      }
      // TODO(herhut): Maybe it would be better to do this breadth first.
      if ((className.matches(iface) && containsAnnotation(annotation, ifaceClass.annotations))
          || anyImplementedInterfaceMatches(ifaceClass, className, annotation)) {
        return true;
      }
    }
    if (clazz.superType == null) {
      return false;
    }
    DexClass superClass = application.definitionFor(clazz.superType);
    if (superClass == null) {
      // TODO(herhut): Warn about broken supertype chain?
      return false;
    }
    return anyImplementedInterfaceMatches(superClass, className, annotation);
  }

  // Process a class with the keep rule.
  private void process(DexClass clazz, ProguardConfigurationRule rule) {
    if (!rule.getClassAccessFlags().containsAll(clazz.accessFlags)) {
      return;
    }
    if (!rule.getNegatedClassAccessFlags().containsNone(clazz.accessFlags)) {
      return;
    }
    if (!containsAnnotation(rule.getClassAnnotation(), clazz.annotations)) {
      return;
    }

    // In principle it should make a difference whether the user specified in a class
    // spec that a class either extends or implements another type. However, proguard
    // seems not to care, so users have started to use this inconsistently. We are thus
    // inconsistent, as well, but tell them.
    // TODO(herhut): One day make this do what it says.
    if (rule.hasInheritanceClassName()) {
      boolean extendsExpected =
          anySuperTypeMatches(clazz.superType, rule.getInheritanceClassName(),
              rule.getInheritanceAnnotation());
      boolean implementsExpected = false;
      if (!extendsExpected) {
        implementsExpected =
            anyImplementedInterfaceMatches(clazz, rule.getInheritanceClassName(),
                rule.getInheritanceAnnotation());
      }
      if (!extendsExpected && !implementsExpected) {
        return;
      }
      // Warn if users got it wrong, but only warn once.
      if (extendsExpected && !rule.getInheritanceIsExtends()) {
        if (rulesThatUseExtendsOrImplementsWrong.add(rule)) {
          options.diagnosticsHandler.warning(
              new StringDiagnostic(
                  "The rule `" + rule + "` uses implements but actually matches extends."));
        }
      } else if (implementsExpected && rule.getInheritanceIsExtends()) {
        if (rulesThatUseExtendsOrImplementsWrong.add(rule)) {
          options.diagnosticsHandler.warning(
              new StringDiagnostic(
                  "The rule `" + rule + "` uses extends but actually matches implements."));
        }
      }
    }

    if (rule.getClassNames().matches(clazz.type)) {
      Collection<ProguardMemberRule> memberKeepRules = rule.getMemberRules();
      if (rule instanceof ProguardKeepRule) {
        switch (((ProguardKeepRule) rule).getType()) {
          case KEEP_CLASS_MEMBERS: {
            markMatchingVisibleMethods(clazz, memberKeepRules, rule, clazz.type);
            markMatchingFields(clazz, memberKeepRules, rule, clazz.type);
            break;
          }
          case KEEP_CLASSES_WITH_MEMBERS: {
            if (!allRulesSatisfied(memberKeepRules, clazz)) {
              break;
            }
            // fallthrough;
          }
          case KEEP: {
            markClass(clazz, rule);
            markMatchingVisibleMethods(clazz, memberKeepRules, rule, null);
            markMatchingFields(clazz, memberKeepRules, rule, null);
            break;
          }
        }
      } else if (rule instanceof ProguardCheckDiscardRule) {
        if (memberKeepRules.isEmpty()) {
          markClass(clazz, rule);
        } else {
          markMatchingFields(clazz, memberKeepRules, rule, clazz.type);
          markMatchingMethods(clazz, memberKeepRules, rule, clazz.type);
        }
      } else if (rule instanceof ProguardWhyAreYouKeepingRule
          || rule instanceof ProguardKeepPackageNamesRule) {
        markClass(clazz, rule);
        markMatchingVisibleMethods(clazz, memberKeepRules, rule, null);
        markMatchingFields(clazz, memberKeepRules, rule, null);
      } else if (rule instanceof ProguardAssumeNoSideEffectRule) {
        markMatchingVisibleMethods(clazz, memberKeepRules, rule, null);
        markMatchingFields(clazz, memberKeepRules, rule, null);
      } else if (rule instanceof ProguardAlwaysInlineRule) {
        markMatchingMethods(clazz, memberKeepRules, rule, null);
      } else if (rule instanceof ProguardAssumeValuesRule) {
        markMatchingVisibleMethods(clazz, memberKeepRules, rule, null);
        markMatchingFields(clazz, memberKeepRules, rule, null);
      } else {
        assert rule instanceof ProguardIdentifierNameStringRule;
        // TODO(b/36799092): collect string literals while marking class and matching members.
      }
    }
  }

  public RootSet run(ExecutorService executorService) throws ExecutionException {
    application.timing.begin("Build root set...");
    try {
      List<Future<?>> futures = new ArrayList<>();
      // Mark all the things explicitly listed in keep rules.
      if (rules != null) {
        for (ProguardConfigurationRule rule : rules) {
          List<DexType> specifics = rule.getClassNames().asSpecificDexTypes();
          if (specifics != null) {
            // This keep rule only lists specific type matches.
            // This means there is no need to iterate over all classes.
            for (DexType type : specifics) {
              DexClass clazz = application.definitionFor(type);
              // Ignore keep rule iff it does not reference a class in the app.
              if (clazz != null) {
                process(clazz, rule);
              }
            }
          } else {
            futures.add(executorService.submit(() -> {
              for (DexProgramClass clazz : application.classes()) {
                process(clazz, rule);
              }
              if (rule.applyToLibraryClasses()) {
                for (DexLibraryClass clazz : application.libraryClasses()) {
                  process(clazz, rule);
                }
              }
            }));
          }
        }
        ThreadUtils.awaitFutures(futures);
      }
    } finally {
      application.timing.end();
    }
    return new RootSet(
        noShrinking,
        noOptimization,
        noObfuscation,
        reasonAsked,
        keepPackageName,
        checkDiscarded,
        alwaysInline,
        noSideEffects,
        assumedValues,
        dependentNoShrinking);
  }

  private void markMatchingVisibleMethods(DexClass clazz,
      Collection<ProguardMemberRule> memberKeepRules, ProguardConfigurationRule rule,
      DexType onlyIfClassKept) {
    Set<Wrapper<DexMethod>> methodsMarked = new HashSet<>();
    Arrays.stream(clazz.directMethods()).forEach(method ->
        markMethod(method, memberKeepRules, rule, methodsMarked, onlyIfClassKept));
    while (clazz != null) {
      Arrays.stream(clazz.virtualMethods()).forEach(method ->
          markMethod(method, memberKeepRules, rule, methodsMarked, onlyIfClassKept));
      clazz = application.definitionFor(clazz.superType);
    }
  }

  private void markMatchingMethods(DexClass clazz,
      Collection<ProguardMemberRule> memberKeepRules, ProguardConfigurationRule rule,
      DexType onlyIfClassKept) {
    Arrays.stream(clazz.directMethods()).forEach(method ->
        markMethod(method, memberKeepRules, rule, null, onlyIfClassKept));
    Arrays.stream(clazz.virtualMethods()).forEach(method ->
        markMethod(method, memberKeepRules, rule, null, onlyIfClassKept));
  }

  private void markMatchingFields(DexClass clazz,
      Collection<ProguardMemberRule> memberKeepRules, ProguardConfigurationRule rule,
      DexType onlyIfClassKept) {
    clazz.forEachField(field -> markField(field, memberKeepRules, rule, onlyIfClassKept));
  }

  // TODO(67934426): Test this code.
  public static void writeSeeds(AppInfoWithLiveness appInfo, PrintStream out) {
    for (DexItem seed : appInfo.getPinnedItems()) {
      if (seed instanceof DexType) {
        out.println(seed.toSourceString());
      } else if (seed instanceof DexField) {
        DexField field = ((DexField) seed);
        out.println(
            field.clazz.toSourceString() + ": " + field.type.toSourceString() + " " + field.name
                .toSourceString());
      } else if (seed instanceof DexMethod) {
        DexMethod method = (DexMethod) seed;
        out.print(method.holder.toSourceString() + ": ");
        DexEncodedMethod encodedMethod = appInfo.definitionFor(method);
        if (encodedMethod.accessFlags.isConstructor()) {
          if (encodedMethod.accessFlags.isStatic()) {
            out.print(Constants.CLASS_INITIALIZER_NAME);
          } else {
            String holderName = method.holder.toSourceString();
            String constrName = holderName.substring(holderName.lastIndexOf('.') + 1);
            out.print(constrName);
          }
        } else {
          out.print(
              method.proto.returnType.toSourceString() + " " + method.name.toSourceString());
        }
        boolean first = true;
        out.print("(");
        for (DexType param : method.proto.parameters.values) {
          if (!first) {
            out.print(",");
          }
          first = false;
          out.print(param.toSourceString());
        }
        out.println(")");
      } else {
        throw new Unreachable();
      }
    }
    out.close();
  }

  private boolean allRulesSatisfied(Collection<ProguardMemberRule> memberKeepRules,
      DexClass clazz) {
    for (ProguardMemberRule rule : memberKeepRules) {
      if (!ruleSatisfied(rule, clazz)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Checks whether the given rule is satisfied by this clazz, not taking superclasses into
   * account.
   */
  private boolean ruleSatisfied(ProguardMemberRule rule, DexClass clazz) {
    return ruleSatisfiedByMethods(rule, clazz.directMethods())
        || ruleSatisfiedByMethods(rule, clazz.virtualMethods())
        || ruleSatisfiedByFields(rule, clazz.staticFields())
        || ruleSatisfiedByFields(rule, clazz.instanceFields());
  }

  private boolean ruleSatisfiedByMethods(ProguardMemberRule rule, DexEncodedMethod[] methods) {
    if (rule.getRuleType().includesMethods()) {
      for (DexEncodedMethod method : methods) {
        if (rule.matches(method, this)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean ruleSatisfiedByFields(ProguardMemberRule rule, DexEncodedField[] fields) {
    if (rule.getRuleType().includesFields()) {
      for (DexEncodedField field : fields) {
        if (rule.matches(field, this)) {
          return true;
        }
      }
    }
    return false;
  }

  static boolean containsAnnotation(ProguardTypeMatcher classAnnotation,
      DexAnnotationSet annotations) {
    if (classAnnotation == null) {
      return true;
    }
    if (annotations.isEmpty()) {
      return false;
    }
    for (DexAnnotation annotation : annotations.annotations) {
      if (classAnnotation.matches(annotation.annotation.type)) {
        return true;
      }
    }
    return false;
  }

  private final IdentityHashMap<DexString, String> stringCache = new IdentityHashMap<>();
  private final IdentityHashMap<DexType, String> typeCache = new IdentityHashMap<>();

  public String lookupString(DexString name) {
    return stringCache.computeIfAbsent(name, DexString::toString);
  }

  public String lookupType(DexType type) {
    return typeCache.computeIfAbsent(type, DexType::toSourceString);
  }

  private void markMethod(DexEncodedMethod method, Collection<ProguardMemberRule> rules,
      ProguardConfigurationRule context, Set<Wrapper<DexMethod>> methodsMarked,
      DexType onlyIfClassKept) {
    if ((methodsMarked != null)
        && methodsMarked.contains(MethodSignatureEquivalence.get().wrap(method.method))) {
      return;
    }
    for (ProguardMemberRule rule : rules) {
      if (rule.matches(method, this)) {
        if (Log.ENABLED) {
          Log.verbose(getClass(), "Marking method `%s` due to `%s { %s }`.", method, context,
              rule);
        }
        if (methodsMarked != null) {
          methodsMarked.add(MethodSignatureEquivalence.get().wrap(method.method));
        }
        addItemToSets(method, context, rule, onlyIfClassKept);
      }
    }
  }

  private void markField(DexEncodedField field, Collection<ProguardMemberRule> rules,
      ProguardConfigurationRule context, DexType onlyIfClassKept) {
    for (ProguardMemberRule rule : rules) {
      if (rule.matches(field, this)) {
        if (Log.ENABLED) {
          Log.verbose(getClass(), "Marking field `%s` due to `%s { %s }`.", field, context,
              rule);
        }
        addItemToSets(field, context, rule, onlyIfClassKept);
      }
    }
  }

  private void markClass(DexClass clazz, ProguardConfigurationRule rule) {
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Marking class `%s` due to `%s`.", clazz.type, rule);
    }
    addItemToSets(clazz, rule, null, null);
  }

  private void includeDescriptor(DexItem item, DexType type, ProguardKeepRule context) {
    if (type.isArrayType()) {
      type = type.toBaseType(application.dexItemFactory);
    }
    if (type.isPrimitiveType()) {
      return;
    }
    DexClass definition = appInfo.definitionFor(type);
    if (definition == null || definition.isLibraryClass()) {
      return;
    }
    // Keep the type if the item is also kept.
    dependentNoShrinking.computeIfAbsent(item, x -> new IdentityHashMap<>())
        .put(definition, context);
    // Unconditionally add to no-obfuscation, as that is only checked for surviving items.
    noObfuscation.add(type);
  }

  private void includeDescriptorClasses(DexItem item, ProguardKeepRule context) {
    if (item instanceof DexEncodedMethod) {
      DexMethod method = ((DexEncodedMethod) item).method;
      includeDescriptor(item, method.proto.returnType, context);
      for (DexType value : method.proto.parameters.values) {
        includeDescriptor(item, value, context);
      }
    } else if (item instanceof DexEncodedField) {
      DexField field = ((DexEncodedField) item).field;
      includeDescriptor(item, field.type, context);
    } else {
      assert item instanceof DexClass;
    }
  }

  private synchronized void addItemToSets(DexItem item, ProguardConfigurationRule context,
      ProguardMemberRule rule, DexType onlyIfClassKept) {
    if (context instanceof ProguardKeepRule) {
      ProguardKeepRule keepRule = (ProguardKeepRule) context;
      ProguardKeepRuleModifiers modifiers = keepRule.getModifiers();
      if (!modifiers.allowsShrinking) {
        if (onlyIfClassKept != null) {
          dependentNoShrinking.computeIfAbsent(onlyIfClassKept, x -> new IdentityHashMap<>())
              .put(item, keepRule);
        } else {
          noShrinking.put(item, keepRule);
        }
      }
      if (!modifiers.allowsOptimization) {
        noOptimization.add(item);
      }
      if (!modifiers.allowsObfuscation) {
        if (item instanceof DexClass) {
          noObfuscation.add(((DexClass) item).type);
        } else {
          noObfuscation.add(item);
        }
      }
      if (modifiers.includeDescriptorClasses) {
        includeDescriptorClasses(item, keepRule);
      }
    } else if (context instanceof ProguardAssumeNoSideEffectRule) {
      noSideEffects.put(item, rule);
    } else if (context instanceof ProguardWhyAreYouKeepingRule) {
      reasonAsked.add(item);
    } else if (context instanceof ProguardKeepPackageNamesRule) {
      keepPackageName.add(item);
    } else if (context instanceof ProguardAssumeValuesRule) {
      assumedValues.put(item, rule);
    } else if (context instanceof ProguardCheckDiscardRule) {
      checkDiscarded.add(item);
    } else if (context instanceof ProguardAlwaysInlineRule) {
      alwaysInline.add(item);
    }
  }

  public static class RootSet {

    public final Map<DexItem, ProguardKeepRule> noShrinking;
    public final Set<DexItem> noOptimization;
    public final Set<DexItem> noObfuscation;
    public final Set<DexItem> reasonAsked;
    public final Set<DexItem> keepPackageName;
    public final Set<DexItem> checkDiscarded;
    public final Set<DexItem> alwaysInline;
    public final Map<DexItem, ProguardMemberRule> noSideEffects;
    public final Map<DexItem, ProguardMemberRule> assumedValues;
    private final Map<DexItem, Map<DexItem, ProguardKeepRule>> dependentNoShrinking;

    private boolean isTypeEncodedMethodOrEncodedField(DexItem item) {
      assert item instanceof DexType
          || item instanceof DexEncodedMethod
          || item instanceof DexEncodedField;
      return item instanceof DexType
          || item instanceof DexEncodedMethod
          || item instanceof DexEncodedField;
    }

    private boolean legalNoObfuscationItems(Set<DexItem> items) {
      assert items.stream().allMatch(this::isTypeEncodedMethodOrEncodedField);
      return true;
    }

    private boolean legalDependentNoShrinkingItems(
        Map<DexItem, Map<DexItem, ProguardKeepRule>> dependentNoShrinking) {
      assert dependentNoShrinking.keySet().stream()
          .allMatch(this::isTypeEncodedMethodOrEncodedField);
      return true;
    }

    private RootSet(
        Map<DexItem, ProguardKeepRule> noShrinking,
        Set<DexItem> noOptimization,
        Set<DexItem> noObfuscation,
        Set<DexItem> reasonAsked,
        Set<DexItem> keepPackageName,
        Set<DexItem> checkDiscarded,
        Set<DexItem> alwaysInline,
        Map<DexItem, ProguardMemberRule> noSideEffects,
        Map<DexItem, ProguardMemberRule> assumedValues,
        Map<DexItem, Map<DexItem, ProguardKeepRule>> dependentNoShrinking) {
      this.noShrinking = Collections.unmodifiableMap(noShrinking);
      this.noOptimization = Collections.unmodifiableSet(noOptimization);
      this.noObfuscation = Collections.unmodifiableSet(noObfuscation);
      this.reasonAsked = Collections.unmodifiableSet(reasonAsked);
      this.keepPackageName = Collections.unmodifiableSet(keepPackageName);
      this.checkDiscarded = Collections.unmodifiableSet(checkDiscarded);
      this.alwaysInline = Collections.unmodifiableSet(alwaysInline);
      this.noSideEffects = Collections.unmodifiableMap(noSideEffects);
      this.assumedValues = Collections.unmodifiableMap(assumedValues);
      this.dependentNoShrinking = dependentNoShrinking;
      assert legalNoObfuscationItems(noObfuscation);
      assert legalDependentNoShrinkingItems(dependentNoShrinking);
    }

    Map<DexItem, ProguardKeepRule> getDependentItems(DexItem item) {
      assert item instanceof DexType
          || item instanceof DexEncodedMethod
          || item instanceof DexEncodedField;
      return Collections
          .unmodifiableMap(dependentNoShrinking.getOrDefault(item, Collections.emptyMap()));
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append("RootSet");

      builder.append("\nnoShrinking: " + noShrinking.size());
      builder.append("\nnoOptimization: " + noOptimization.size());
      builder.append("\nnoObfuscation: " + noObfuscation.size());
      builder.append("\nreasonAsked: " + reasonAsked.size());
      builder.append("\nkeepPackageName: " + keepPackageName.size());
      builder.append("\ncheckDiscarded: " + checkDiscarded.size());
      builder.append("\nnoSideEffects: " + noSideEffects.size());
      builder.append("\nassumedValues: " + assumedValues.size());
      builder.append("\ndependentNoShrinking: " + dependentNoShrinking.size());

      builder.append("\n\nNo Shrinking:");
      noShrinking.keySet().stream()
          .sorted(Comparator.comparing(DexItem::toSourceString))
          .forEach(a -> builder
              .append("\n").append(a.toSourceString()).append(" ").append(noShrinking.get(a)));
      builder.append("\n");
      return builder.toString();
    }
  }
}
