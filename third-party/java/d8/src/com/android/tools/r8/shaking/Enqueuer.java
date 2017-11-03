// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.Descriptor;
import com.android.tools.r8.graph.DexAnnotation;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexCallSite;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItem;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexMethodHandle;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DirectMappedDexApplication;
import com.android.tools.r8.graph.GraphLense;
import com.android.tools.r8.graph.KeyedDexItem;
import com.android.tools.r8.graph.PresortedComparable;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.shaking.RootSetBuilder.RootSet;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.MethodSignatureEquivalence;
import com.android.tools.r8.utils.Timing;
import com.google.common.base.Equivalence.Wrapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Approximates the runtime dependencies for the given set of roots.
 *
 * <p>The implementation filters the static call-graph with liveness information on classes to
 * remove virtual methods that are reachable by their static type but are unreachable at runtime as
 * they are not visible from any instance.
 *
 * <p>As result of the analysis, an instance of {@link AppInfoWithLiveness} is returned. See the
 * field descriptions for details.
 */
public class Enqueuer {

  private final AppInfoWithSubtyping appInfo;
  private final InternalOptions options;
  private RootSet rootSet;

  private Map<DexType, Set<DexMethod>> virtualInvokes = Maps.newIdentityHashMap();
  private Map<DexType, Set<DexMethod>> superInvokes = Maps.newIdentityHashMap();
  private Map<DexType, Set<DexMethod>> directInvokes = Maps.newIdentityHashMap();
  private Map<DexType, Set<DexMethod>> staticInvokes = Maps.newIdentityHashMap();
  private Map<DexType, Set<DexField>> instanceFieldsWritten = Maps.newIdentityHashMap();
  private Map<DexType, Set<DexField>> instanceFieldsRead = Maps.newIdentityHashMap();
  private Map<DexType, Set<DexField>> staticFieldsRead = Maps.newIdentityHashMap();
  private Map<DexType, Set<DexField>> staticFieldsWritten = Maps.newIdentityHashMap();

  private final List<SemanticsProvider> extensions = new ArrayList<>();
  private final Map<Class<?>, Object> extensionsState = new HashMap<>();

  /**
   * This map keeps a view of all virtual methods that are reachable from virtual invokes. A method
   * is reachable even if no live subtypes exist, so this is not sufficient for inclusion in the
   * live set.
   */
  private Map<DexType, SetWithReason<DexEncodedMethod>> reachableVirtualMethods = Maps
      .newIdentityHashMap();
  /**
   * Tracks the dependency between a method and the super-method it calls, if any. Used to make
   * super methods become live when they become reachable from a live sub-method.
   */
  private Map<DexEncodedMethod, Set<DexEncodedMethod>> superInvokeDependencies = Maps
      .newIdentityHashMap();
  /**
   * Set of instance fields that can be reached by read/write operations.
   */
  private Map<DexType, SetWithReason<DexEncodedField>> reachableInstanceFields = Maps
      .newIdentityHashMap();

  /**
   * Set of types that are mentioned in the program. We at least need an empty abstract classitem
   * for these.
   */
  private Set<DexType> liveTypes = Sets.newIdentityHashSet();
  /**
   * Set of types that are actually instantiated. These cannot be abstract.
   */
  private SetWithReason<DexType> instantiatedTypes = new SetWithReason<>();
  /**
   * Set of methods that are the immediate target of an invoke. They might not actually be live but
   * are required so that invokes can find the method. If a method is only a target but not live,
   * its implementation may be removed and it may be marked abstract.
   */
  private SetWithReason<DexEncodedMethod> targetedMethods = new SetWithReason<>();
  /**
   * Set of methods that belong to live classes and can be reached by invokes. These need to be
   * kept.
   */
  private SetWithReason<DexEncodedMethod> liveMethods = new SetWithReason<>();

  /**
   * Set of fields that belong to live classes and can be reached by invokes. These need to be
   * kept.
   */
  private SetWithReason<DexEncodedField> liveFields = new SetWithReason<>();

  /**
   * A queue of items that need processing. Different items trigger different actions:
   */
  private Queue<Action> workList = Queues.newArrayDeque();

  /**
   * A cache for DexMethod that have been marked reachable.
   */
  private Set<DexMethod> virtualTargetsMarkedAsReachable = Sets.newIdentityHashSet();

  /**
   * A set of dexitems we have reported missing to dedupe warnings.
   */
  private Set<DexItem> reportedMissing = Sets.newIdentityHashSet();

  /**
   * A set of items that we are keeping due to keep rules. This may differ from the rootSet
   * due to dependent keep rules.
   */
  private Set<DexItem> pinnedItems = Sets.newIdentityHashSet();

  /**
   * A map from classes to annotations that need to be processed should the classes ever
   * become live.
   */
  private final Map<DexType, Set<DexAnnotation>> deferredAnnotations = new IdentityHashMap<>();

  public Enqueuer(AppInfoWithSubtyping appInfo, InternalOptions options) {
    this.appInfo = appInfo;
    this.options = options;
  }

  public void addExtension(SemanticsProvider extension) {
    extensions.add(extension);
  }

  private void enqueueRootItems(Map<DexItem, ProguardKeepRule> items) {
    workList.addAll(
        items.entrySet().stream().map(Action::forRootItem).collect(Collectors.toList()));
    pinnedItems.addAll(items.keySet());
  }

  //
  // Things to do with registering events. This is essentially the interface for byte-code
  // traversals.
  //

  private <S extends DexItem, T extends Descriptor<S, T>> boolean registerItemWithTarget(
      Map<DexType, Set<T>> seen, T item) {
    DexType holder = item.getHolder();
    if (holder.isArrayType()) {
      holder = holder.toBaseType(appInfo.dexItemFactory);
    }
    if (!holder.isClassType()) {
      return false;
    }
    markTypeAsLive(holder);
    return seen.computeIfAbsent(item.getHolder(), (ignore) -> Sets.newIdentityHashSet()).add(item);
  }

  private class UseRegistry extends com.android.tools.r8.graph.UseRegistry {

    private final DexEncodedMethod currentMethod;

    private UseRegistry(DexEncodedMethod currentMethod) {
      this.currentMethod = currentMethod;
    }

    @Override
    public boolean registerInvokeVirtual(DexMethod method) {
      if (!registerItemWithTarget(virtualInvokes, method)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register invokeVirtual `%s`.", method);
      }
      workList.add(Action.markReachableVirtual(method, KeepReason.invokedFrom(currentMethod)));
      return true;
    }

    @Override
    public boolean registerInvokeDirect(DexMethod method) {
      if (!registerItemWithTarget(directInvokes, method)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register invokeDirect `%s`.", method);
      }
      handleInvokeOfDirectTarget(method, KeepReason.invokedFrom(currentMethod));
      return true;
    }

    @Override
    public boolean registerInvokeStatic(DexMethod method) {
      if (!registerItemWithTarget(staticInvokes, method)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register invokeStatic `%s`.", method);
      }
      handleInvokeOfStaticTarget(method, KeepReason.invokedFrom(currentMethod));
      return true;
    }

    @Override
    public boolean registerInvokeInterface(DexMethod method) {
      if (!registerItemWithTarget(virtualInvokes, method)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register invokeInterface `%s`.", method);
      }
      workList.add(Action.markReachableInterface(method, KeepReason.invokedFrom(currentMethod)));
      return true;
    }

    @Override
    public boolean registerInvokeSuper(DexMethod method) {
      if (!registerItemWithTarget(superInvokes, method)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register invokeSuper `%s`.", method);
      }
      workList.add(Action.markReachableSuper(method, currentMethod));
      return true;
    }

    @Override
    public boolean registerInstanceFieldWrite(DexField field) {
      if (!registerItemWithTarget(instanceFieldsWritten, field)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register Iput `%s`.", field);
      }
      // TODO(herhut): We have to add this, but DCR should eliminate dead writes.
      workList.add(Action.markReachableField(field, KeepReason.fieldReferencedIn(currentMethod)));
      return true;
    }

    @Override
    public boolean registerInstanceFieldRead(DexField field) {
      if (!registerItemWithTarget(instanceFieldsRead, field)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register Iget `%s`.", field);
      }
      workList.add(Action.markReachableField(field, KeepReason.fieldReferencedIn(currentMethod)));
      return true;
    }

    @Override
    public boolean registerNewInstance(DexType type) {
      if (instantiatedTypes.contains(type)) {
        return false;
      }
      DexClass clazz = appInfo.definitionFor(type);
      if (clazz == null) {
        reportMissingClass(type);
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register new instatiation of `%s`.", clazz);
      }
      workList.add(Action.markInstantiated(clazz, KeepReason.instantiatedIn(currentMethod)));
      return true;
    }

    @Override
    public boolean registerStaticFieldRead(DexField field) {
      if (!registerItemWithTarget(staticFieldsRead, field)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register Sget `%s`.", field);
      }
      markStaticFieldAsLive(field, KeepReason.fieldReferencedIn(currentMethod));
      return true;
    }

    @Override
    public boolean registerStaticFieldWrite(DexField field) {
      if (!registerItemWithTarget(staticFieldsWritten, field)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register Sput `%s`.", field);
      }
      // TODO(herhut): We have to add this, but DCR should eliminate dead writes.
      markStaticFieldAsLive(field, KeepReason.fieldReferencedIn(currentMethod));
      return true;
    }

    @Override
    public boolean registerTypeReference(DexType type) {
      DexType baseType = type.toBaseType(appInfo.dexItemFactory);
      if (baseType.isClassType()) {
        markTypeAsLive(baseType);
        return true;
      }
      return false;
    }

    @Override
    public boolean registerConstClass(DexType type) {
      boolean result = registerTypeReference(type);
      // For Proguard compatibility mark default initializer for live type as live.
      if (options.forceProguardCompatibility) {
        if (type.isArrayType()) {
          return result;
        }
        assert type.isClassType();
        DexClass holder = appInfo.definitionFor(type);
        if (holder == null) {
          // Don't call reportMissingClass(type) here. That already happened in the call to
          // registerTypeReference(type).
          return result;
        }
        if (holder.hasDefaultInitializer()) {
          registerNewInstance(type);
          DexEncodedMethod init = holder.getDefaultInitializer();
          markDirectStaticOrConstructorMethodAsLive(init, KeepReason.reachableFromLiveType(type));
        }
      }
      return result;
    }
  }

  //
  // Actual actions performed.
  //

  private void markTypeAsLive(DexType type) {
    assert type.isClassType();
    if (liveTypes.add(type)) {
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Type `%s` has become live.", type);
      }
      DexClass holder = appInfo.definitionFor(type);
      if (holder == null) {
        reportMissingClass(type);
        return;
      }
      for (DexType iface : holder.interfaces.values) {
        markTypeAsLive(iface);
      }
      if (holder.superType != null) {
        markTypeAsLive(holder.superType);
      }
      if (!holder.annotations.isEmpty()) {
        processAnnotations(holder.annotations.annotations);
      }
      // We also need to add the corresponding <clinit> to the set of live methods, as otherwise
      // static field initialization (and other class-load-time sideeffects) will not happen.
      if (holder.hasNonTrivialClassInitializer()) {
        DexEncodedMethod clinit = holder.getClassInitializer();
        markDirectStaticOrConstructorMethodAsLive(clinit, KeepReason.reachableFromLiveType(type));
      }

      // If this type has deferred annotations, we have to process those now, too.
      Set<DexAnnotation> annotations = deferredAnnotations.remove(type);
      if (annotations != null) {
        annotations.forEach(this::handleAnnotationOfLiveType);
      }

      // For Proguard compatibility mark default initializer for live type as live.
      if (options.forceProguardCompatibility) {
        if (holder.hasDefaultInitializer()) {
          DexEncodedMethod init = holder.getDefaultInitializer();
          markDirectStaticOrConstructorMethodAsLive(init, KeepReason.reachableFromLiveType(type));
        }
      }
    }
  }

  private void handleAnnotationOfLiveType(DexAnnotation annotation) {
    AnnotationReferenceMarker referenceMarker = new AnnotationReferenceMarker(
        annotation.annotation.type, appInfo.dexItemFactory);
    annotation.annotation.collectIndexedItems(referenceMarker);
  }

  private void processAnnotations(DexAnnotation[] annotations) {
    for (DexAnnotation annotation : annotations) {
      DexType type = annotation.annotation.type;
      if (liveTypes.contains(type)) {
        // The type of this annotation is already live, so pick up its dependencies.
        handleAnnotationOfLiveType(annotation);
      } else {
        // Remember this annotation for later.
        deferredAnnotations.computeIfAbsent(type, ignore -> new HashSet<>()).add(annotation);
      }
    }
  }

  private void handleInvokeOfStaticTarget(DexMethod method, KeepReason reason) {
    DexEncodedMethod encodedMethod = appInfo.lookupStaticTarget(method);
    if (encodedMethod == null) {
      reportMissingMethod(method);
      return;
    }
    markDirectStaticOrConstructorMethodAsLive(encodedMethod, reason);
  }

  private void handleInvokeOfDirectTarget(DexMethod method, KeepReason reason) {
    DexEncodedMethod encodedMethod = appInfo.lookupDirectTarget(method);
    if (encodedMethod == null) {
      reportMissingMethod(method);
      return;
    }
    markDirectStaticOrConstructorMethodAsLive(encodedMethod, reason);
  }

  private void reportMissingClass(DexType clazz) {
    if (Log.ENABLED && reportedMissing.add(clazz)) {
      Log.verbose(Enqueuer.class, "Class `%s` is missing.", clazz);
    }
  }

  private void reportMissingMethod(DexMethod method) {
    if (Log.ENABLED && reportedMissing.add(method)) {
      Log.verbose(Enqueuer.class, "Method `%s` is missing.", method);
    }
  }

  private void reportMissingField(DexField field) {
    if (Log.ENABLED && reportedMissing.add(field)) {
      Log.verbose(Enqueuer.class, "Field `%s` is missing.", field);
    }
  }

  private void markMethodAsTargeted(DexEncodedMethod encodedMethod, KeepReason reason) {
    markTypeAsLive(encodedMethod.method.holder);
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Method `%s` is targeted.", encodedMethod.method);
    }
    targetedMethods.add(encodedMethod, reason);
  }

  /**
   * Adds the class to the set of instantiated classes and marks its fields and methods live
   * depending on the currently seen invokes and field reads.
   */
  private void processNewlyInstantiatedClass(DexClass clazz, KeepReason reason) {
    if (!instantiatedTypes.add(clazz.type, reason)) {
      return;
    }
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Class `%s` is instantiated, processing...", clazz);
    }
    // This class becomes live, so it and all its supertypes become live types.
    markTypeAsLive(clazz.type);
    // For all methods of the class, if we have seen a call, mark the method live.
    // We only do this for virtual calls, as the other ones will be done directly.
    transitionMethodsForInstantiatedClass(clazz.type);
    // For all instance fields visible from the class, mark them live if we have seen a read.
    transitionFieldsForInstantiatedClass(clazz.type);
    // Add all dependent members to the workqueue.
    enqueueRootItems(rootSet.getDependentItems(clazz.type));
  }

  /**
   * Marks all methods live that can be reached by calls previously seen.
   *
   * <p>This should only be invoked if the given type newly becomes instantiated. In essence, this
   * method replays all the invokes we have seen so far that could apply to this type and marks
   * the corresponding methods live.
   *
   * <p>Only methods that are visible in this type are considered. That is, only those methods that
   * are either defined directly on this type or that are defined on a supertype but are not
   * shadowed by another inherited method.
   */
  private void transitionMethodsForInstantiatedClass(DexType type) {
    Set<Wrapper<DexMethod>> seen = new HashSet<>();
    MethodSignatureEquivalence equivalence = MethodSignatureEquivalence.get();
    do {
      DexClass clazz = appInfo.definitionFor(type);
      if (clazz == null) {
        reportMissingClass(type);
        // TODO(herhut): In essence, our subtyping chain is broken here. Handle that case better.
        break;
      }
      SetWithReason<DexEncodedMethod> reachableMethods = reachableVirtualMethods.get(type);
      if (reachableMethods != null) {
        for (DexEncodedMethod encodedMethod : reachableMethods.getItems()) {
          Wrapper<DexMethod> ignoringClass = equivalence.wrap(encodedMethod.method);
          if (!seen.contains(ignoringClass)) {
            seen.add(ignoringClass);
            markVirtualMethodAsLive(encodedMethod, KeepReason.reachableFromLiveType(type));
          }
        }
      }
      type = clazz.superType;
    } while (type != null && !instantiatedTypes.contains(type));
  }

  /**
   * Marks all fields live that can be reached by a read assuming that the given type or one of
   * its subtypes is instantiated.
   */
  private void transitionFieldsForInstantiatedClass(DexType type) {
    do {
      DexClass clazz = appInfo.definitionFor(type);
      if (clazz == null) {
        // TODO(herhut) The subtype chain is broken. We need a way to deal with this better.
        reportMissingClass(type);
        break;
      }
      SetWithReason<DexEncodedField> reachableFields = reachableInstanceFields.get(type);
      if (reachableFields != null) {
        for (DexEncodedField field : reachableFields.getItems()) {
          markInstanceFieldAsLive(field, KeepReason.reachableFromLiveType(type));
        }
      }
      type = clazz.superType;
    } while (type != null && !instantiatedTypes.contains(type));
  }

  private void markStaticFieldAsLive(DexField field, KeepReason reason) {
    // Mark the type live here, so that the class exists at runtime. Note that this also marks all
    // supertypes as live, so even if the field is actually on a supertype, its class will be live.
    markTypeAsLive(field.clazz);
    // Find the actual field.
    DexEncodedField encodedField = appInfo.lookupStaticTarget(field.clazz, field);
    if (encodedField == null) {
      reportMissingField(field);
      return;
    }
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Adding static field `%s` to live set.", encodedField.field);
    }
    liveFields.add(encodedField, reason);
    // Add all dependent members to the workqueue.
    enqueueRootItems(rootSet.getDependentItems(encodedField));
  }

  private void markInstanceFieldAsLive(DexEncodedField field, KeepReason reason) {
    assert field != null;
    markTypeAsLive(field.field.clazz);
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Adding instance field `%s` to live set.", field.field);
    }
    liveFields.add(field, reason);
    // Add all dependent members to the workqueue.
    enqueueRootItems(rootSet.getDependentItems(field));
  }

  private void markDirectStaticOrConstructorMethodAsLive(
      DexEncodedMethod encodedMethod, KeepReason reason) {
    assert encodedMethod != null;
    if (!liveMethods.contains(encodedMethod)) {
      markTypeAsLive(encodedMethod.method.holder);
      markMethodAsTargeted(encodedMethod, reason);
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Method `%s` has become live due to direct invoke",
            encodedMethod.method);
      }
      workList.add(Action.markMethodLive(encodedMethod, reason));
    }
  }

  private void markVirtualMethodAsLive(DexEncodedMethod method, KeepReason reason) {
    assert method != null;
    if (!liveMethods.contains(method)) {
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Adding virtual method `%s` to live set.", method.method);
      }
      workList.add(Action.markMethodLive(method, reason));
    }
  }

  private boolean isInstantiatedOrHasInstantiatedSubtype(DexType type) {
    return instantiatedTypes.contains(type)
        || appInfo.subtypes(type).stream().anyMatch(instantiatedTypes::contains);
  }

  private void markFieldAsReachable(DexField field, KeepReason reason) {
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Marking instance field `%s` as reachable.", field);
    }
    DexEncodedField encodedField = appInfo.lookupInstanceTarget(field.clazz, field);
    if (encodedField == null) {
      reportMissingField(field);
      return;
    }
    SetWithReason<DexEncodedField> reachable = reachableInstanceFields
        .computeIfAbsent(encodedField.field.clazz, ignore -> new SetWithReason<>());
    if (reachable.add(encodedField, reason) && isInstantiatedOrHasInstantiatedSubtype(
        encodedField.field.clazz)) {
      // We have at least one live subtype, so mark it as live.
      markInstanceFieldAsLive(encodedField, reason);
    }
  }

  private void markVirtualMethodAsReachable(DexMethod method, boolean interfaceInvoke,
      KeepReason reason) {
    if (!virtualTargetsMarkedAsReachable.add(method)) {
      return;
    }
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Marking virtual method `%s` as reachable.", method);
    }
    if (method.holder.isArrayType()) {
      // This is an array type, so the actual class will be generated at runtime. We treat this
      // like an invoke on a direct subtype of java.lang.Object that has no further subtypes.
      // As it has no subtypes, it cannot affect liveness of the program we are processing.
      // Ergo, we can ignore it. We need to make sure that the element type is available, though.
      DexType baseType = method.holder.toBaseType(appInfo.dexItemFactory);
      if (baseType.isClassType()) {
        markTypeAsLive(baseType);
      }
      return;
    }
    DexClass holder = appInfo.definitionFor(method.holder);
    if (holder == null) {
      reportMissingClass(method.holder);
      return;
    }
    DexEncodedMethod topTarget = appInfo.lookupVirtualDefinition(method.holder, method);
    if (topTarget == null) {
      reportMissingMethod(method);
      return;
    }
    // We have to mark this as targeted, as even if this specific instance never becomes live, we
    // need at least an abstract version of it so that we have a target for the corresponding
    // invoke.
    markMethodAsTargeted(topTarget, reason);
    Set<DexEncodedMethod> targets;
    if (interfaceInvoke) {
      if (!holder.isInterface()) {
        throw new CompilationError(
            "InvokeInterface on non-interface method " + method.toSourceString() + ".");
      }
      targets = appInfo.lookupInterfaceTargets(method);
    } else {
      if (holder.isInterface()) {
        throw new CompilationError(
            "InvokeVirtual on interface method " + method.toSourceString() + ".");
      }
      targets = appInfo.lookupVirtualTargets(method);
    }
    for (DexEncodedMethod encodedMethod : targets) {
      SetWithReason<DexEncodedMethod> reachable = reachableVirtualMethods
          .computeIfAbsent(encodedMethod.method.holder, (ignore) -> new SetWithReason<>());
      if (reachable.add(encodedMethod, reason)) {
        // If the holder type is instantiated, the method is live. Otherwise check whether we find
        // a subtype that does not shadow this methods but is instantiated.
        // Note that library classes are always considered instantiated, as we do not know where
        // they are instantiated.
        if (isInstantiatedOrHasInstantiatedSubtype(encodedMethod.method.holder)) {
          if (instantiatedTypes.contains(encodedMethod.method.holder)) {
            markVirtualMethodAsLive(encodedMethod,
                KeepReason.reachableFromLiveType(encodedMethod.method.holder));
          } else {
            Deque<DexType> worklist = new ArrayDeque<>();
            fillWorkList(worklist, encodedMethod.method.holder);
            while (!worklist.isEmpty()) {
              DexType current = worklist.pollFirst();
              DexClass currentHolder = appInfo.definitionFor(current);
              if (currentHolder == null
                  || currentHolder.findVirtualTarget(encodedMethod.method) != null) {
                continue;
              }
              if (instantiatedTypes.contains(current)) {
                markVirtualMethodAsLive(encodedMethod, KeepReason.reachableFromLiveType(current));
                break;
              }
              fillWorkList(worklist, current);
            }
          }
        }
      }
    }
  }

  private static void fillWorkList(Deque<DexType> worklist, DexType type) {
    if (type.isInterface()) {
      // We need to check if the method is shadowed by a class that directly implements
      // the interface and go recursively down to the sub interfaces to reach class
      // implementing the interface
      type.forAllImplementsSubtypes(worklist::addLast);
      type.forAllExtendsSubtypes(worklist::addLast);
    } else {
      type.forAllExtendsSubtypes(worklist::addLast);
    }
  }

  private void markSuperMethodAsReachable(DexMethod method, DexEncodedMethod from) {
    DexEncodedMethod target = appInfo.lookupVirtualTarget(method.holder, method);
    if (target == null) {
      reportMissingMethod(method);
      return;
    }
    assert !superInvokeDependencies.containsKey(from) || !superInvokeDependencies.get(from)
        .contains(target);
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Adding super constraint from `%s` to `%s`", from.method,
          target.method);
    }
    superInvokeDependencies.computeIfAbsent(from, ignore -> Sets.newIdentityHashSet()).add(target);
    if (liveMethods.contains(from)) {
      markMethodAsTargeted(target, KeepReason.invokedViaSuperFrom(from));
      markVirtualMethodAsLive(target, KeepReason.invokedViaSuperFrom(from));
    }
  }

  public ReasonPrinter getReasonPrinter(Set<DexItem> queriedItems) {
    // If no reason was asked, just return a no-op printer to avoid computing the information.
    // This is the common path.
    if (queriedItems.isEmpty()) {
      return ReasonPrinter.getNoOpPrinter();
    }
    Map<DexItem, KeepReason> reachability = new HashMap<>();
    for (SetWithReason<DexEncodedMethod> mappings : reachableVirtualMethods.values()) {
      reachability.putAll(mappings.getReasons());
    }
    for (SetWithReason<DexEncodedField> mappings : reachableInstanceFields.values()) {
      reachability.putAll(mappings.getReasons());
    }
    return new ReasonPrinter(queriedItems, liveFields.getReasons(), liveMethods.getReasons(),
        reachability, instantiatedTypes.getReasons());
  }

  public Set<DexType> traceMainDex(RootSet rootSet, Timing timing) {
    this.rootSet = rootSet;
    // Translate the result of root-set computation into enqueuer actions.
    enqueueRootItems(rootSet.noShrinking);
    AppInfoWithLiveness appInfo = trace(timing);

    // LiveTypes is the result, just make a copy because further work will modify its content.
    return new HashSet<>(appInfo.liveTypes);
  }

  public AppInfoWithLiveness traceApplication(RootSet rootSet, Timing timing) {
    this.rootSet = rootSet;
    // Translate the result of root-set computation into enqueuer actions.
    enqueueRootItems(rootSet.noShrinking);
    appInfo.libraryClasses().forEach(this::markAllVirtualMethodsReachable);
    return trace(timing);
  }

  private AppInfoWithLiveness trace(Timing timing) {
    timing.begin("Grow the tree.");
    try {
      while (!workList.isEmpty()) {
        Action action = workList.poll();
        switch (action.kind) {
          case MARK_INSTANTIATED:
            processNewlyInstantiatedClass((DexClass) action.target, action.reason);
            break;
          case MARK_REACHABLE_FIELD:
            markFieldAsReachable((DexField) action.target, action.reason);
            break;
          case MARK_REACHABLE_VIRTUAL:
            markVirtualMethodAsReachable((DexMethod) action.target, false, action.reason);
            break;
          case MARK_REACHABLE_INTERFACE:
            markVirtualMethodAsReachable((DexMethod) action.target, true, action.reason);
            break;
          case MARK_REACHABLE_SUPER:
            markSuperMethodAsReachable((DexMethod) action.target,
                (DexEncodedMethod) action.context);
            break;
          case MARK_METHOD_KEPT:
            markMethodAsKept((DexEncodedMethod) action.target, action.reason);
            break;
          case MARK_FIELD_KEPT:
            markFieldAsKept((DexEncodedField) action.target, action.reason);
            break;
          case MARK_METHOD_LIVE:
            processNewlyLiveMethod(((DexEncodedMethod) action.target), action.reason);
            break;
          default:
            throw new IllegalArgumentException(action.kind.toString());
        }
      }
      if (Log.ENABLED) {
        Set<DexEncodedMethod> allLive = Sets.newIdentityHashSet();
        for (Entry<DexType, SetWithReason<DexEncodedMethod>> entry : reachableVirtualMethods
            .entrySet()) {
          allLive.addAll(entry.getValue().getItems());
        }
        Set<DexEncodedMethod> reachableNotLive = Sets.difference(allLive, liveMethods.getItems());
        Log.debug(getClass(), "%s methods are reachable but not live", reachableNotLive.size());
        Log.info(getClass(), "Only reachable: %s", reachableNotLive);
        Set<DexType> liveButNotInstantiated =
            Sets.difference(liveTypes, instantiatedTypes.getItems());
        Log.debug(getClass(), "%s classes are live but not instantiated",
            liveButNotInstantiated.size());
        Log.info(getClass(), "Live but not instantiated: %s", liveButNotInstantiated);
        SetView<DexEncodedMethod> targetedButNotLive = Sets
            .difference(targetedMethods.getItems(), liveMethods.getItems());
        Log.debug(getClass(), "%s methods are targeted but not live", targetedButNotLive.size());
        Log.info(getClass(), "Targeted but not live: %s", targetedButNotLive);
      }
      assert liveTypes.stream().allMatch(DexType::isClassType);
      assert instantiatedTypes.getItems().stream().allMatch(DexType::isClassType);
    } finally {
      timing.end();
    }
    return new AppInfoWithLiveness(appInfo, this);
  }

  private void markMethodAsKept(DexEncodedMethod target, KeepReason reason) {
    DexClass holder = appInfo.definitionFor(target.method.holder);
    // If this method no longer has a corresponding class then we have shaken it away before.
    if (holder == null) {
      return;
    }
    if (!target.accessFlags.isStatic()
        && !target.accessFlags.isConstructor()
        && !target.accessFlags.isPrivate()) {
      // A virtual method. Mark it as reachable so that subclasses, if instantiated, keep
      // their overrides. However, we don't mark it live, as a keep rule might not imply that
      // the corresponding class is live.
      markVirtualMethodAsReachable(target.method, holder.accessFlags.isInterface(), reason);
    } else {
      markDirectStaticOrConstructorMethodAsLive(target, reason);
    }
  }

  private void markFieldAsKept(DexEncodedField target, KeepReason reason) {
    // If this field no longer has a corresponding class, then we have shaken it away before.
    if (appInfo.definitionFor(target.field.clazz) == null) {
      return;
    }
    if (target.accessFlags.isStatic()) {
      markStaticFieldAsLive(target.field, reason);
    } else {
      markFieldAsReachable(target.field, reason);
    }
  }

  private void markAllVirtualMethodsReachable(DexClass clazz) {
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Marking all methods of library class `%s` as reachable.",
          clazz.type);
    }
    for (DexEncodedMethod encodedMethod : clazz.virtualMethods()) {
      markMethodAsTargeted(encodedMethod, KeepReason.isLibraryMethod());
      markVirtualMethodAsReachable(encodedMethod.method, clazz.isInterface(),
          KeepReason.isLibraryMethod());
    }
  }

  private void processNewlyLiveMethod(DexEncodedMethod method, KeepReason reason) {
    if (liveMethods.add(method, reason)) {
      DexClass holder = appInfo.definitionFor(method.method.holder);
      assert holder != null;
      if (holder.isLibraryClass()) {
        // We do not process library classes.
        return;
      }
      Set<DexEncodedMethod> superCallTargets = superInvokeDependencies.get(method);
      if (superCallTargets != null) {
        for (DexEncodedMethod superCallTarget : superCallTargets) {
          if (Log.ENABLED) {
            Log.verbose(getClass(), "Found super invoke constraint on `%s`.",
                superCallTarget.method);
          }
          markMethodAsTargeted(superCallTarget, KeepReason.invokedViaSuperFrom(method));
          markVirtualMethodAsLive(superCallTarget, KeepReason.invokedViaSuperFrom(method));
        }
      }
      processAnnotations(method.annotations.annotations);
      for (DexAnnotationSet parameterAnnotation : method.parameterAnnotations.values) {
        processAnnotations(parameterAnnotation.annotations);
      }
      boolean processed = false;
      if (!extensions.isEmpty()) {
        for (SemanticsProvider extension : extensions) {
          if (extension.appliesTo(method)) {
            assert extensions.stream().filter(e -> e.appliesTo(method)).count() == 1;
            extensionsState.put(extension.getClass(),
                extension.processMethod(method, new UseRegistry(method),
                    extensionsState.get(extension.getClass())));
            processed = true;
          }
        }
      }
      if (!processed) {
        method.registerReachableDefinitions(new UseRegistry(method));
      }
      // Add all dependent members to the workqueue.
      enqueueRootItems(rootSet.getDependentItems(method));
    }
  }

  private Set<DexField> collectFields(Map<DexType, Set<DexField>> map) {
    return map.values().stream().flatMap(Collection::stream)
        .collect(Collectors.toCollection(Sets::newIdentityHashSet));
  }

  SortedSet<DexField> collectInstanceFieldsRead() {
    return ImmutableSortedSet.copyOf(
        PresortedComparable<DexField>::slowCompareTo, collectFields(instanceFieldsRead));
  }

  SortedSet<DexField> collectInstanceFieldsWritten() {
    return ImmutableSortedSet.copyOf(
        PresortedComparable<DexField>::slowCompareTo, collectFields(instanceFieldsWritten));
  }

  SortedSet<DexField> collectStaticFieldsRead() {
    return ImmutableSortedSet.copyOf(
        PresortedComparable<DexField>::slowCompareTo, collectFields(staticFieldsRead));
  }

  SortedSet<DexField> collectStaticFieldsWritten() {
    return ImmutableSortedSet.copyOf(
        PresortedComparable<DexField>::slowCompareTo, collectFields(staticFieldsWritten));
  }

  private Set<DexField> collectReachedFields(Map<DexType, Set<DexField>> map,
      Function<DexField, DexField> lookup) {
    return map.values().stream().flatMap(set -> set.stream().map(lookup))
        .collect(Collectors.toCollection(Sets::newIdentityHashSet));
  }

  private DexField tryLookupInstanceField(DexField field) {
    DexEncodedField target = appInfo.lookupInstanceTarget(field.clazz, field);
    return target == null ? field : target.field;
  }

  private DexField tryLookupStaticField(DexField field) {
    DexEncodedField target = appInfo.lookupStaticTarget(field.clazz, field);
    return target == null ? field : target.field;
  }

  SortedSet<DexField> collectFieldsRead() {
    return ImmutableSortedSet.copyOf(PresortedComparable<DexField>::slowCompareTo,
        Sets.union(collectReachedFields(instanceFieldsRead, this::tryLookupInstanceField),
            collectReachedFields(staticFieldsRead, this::tryLookupStaticField)));
  }

  SortedSet<DexField> collectFieldsWritten() {
    return ImmutableSortedSet.copyOf(PresortedComparable<DexField>::slowCompareTo,
        Sets.union(collectReachedFields(instanceFieldsWritten, this::tryLookupInstanceField),
            collectReachedFields(staticFieldsWritten, this::tryLookupStaticField)));
  }

  private static class Action {

    final Kind kind;
    final DexItem target;
    final DexItem context;
    final KeepReason reason;

    private Action(Kind kind, DexItem target, DexItem context, KeepReason reason) {
      this.kind = kind;
      this.target = target;
      this.context = context;
      this.reason = reason;
    }

    public static Action markReachableVirtual(DexMethod method, KeepReason reason) {
      return new Action(Kind.MARK_REACHABLE_VIRTUAL, method, null, reason);
    }

    public static Action markReachableInterface(DexMethod method, KeepReason reason) {
      return new Action(Kind.MARK_REACHABLE_INTERFACE, method, null, reason);
    }

    public static Action markReachableSuper(DexMethod method, DexEncodedMethod from) {
      return new Action(Kind.MARK_REACHABLE_SUPER, method, from, null);
    }

    public static Action markReachableField(DexField field, KeepReason reason) {
      return new Action(Kind.MARK_REACHABLE_FIELD, field, null, reason);
    }

    public static Action markInstantiated(DexClass clazz, KeepReason reason) {
      return new Action(Kind.MARK_INSTANTIATED, clazz, null, reason);
    }

    public static Action markMethodLive(DexEncodedMethod method, KeepReason reason) {
      return new Action(Kind.MARK_METHOD_LIVE, method, null, reason);
    }

    public static Action markMethodKept(DexEncodedMethod method, KeepReason reason) {
      return new Action(Kind.MARK_METHOD_KEPT, method, null, reason);
    }

    public static Action markFieldKept(DexEncodedField method, KeepReason reason) {
      return new Action(Kind.MARK_FIELD_KEPT, method, null, reason);
    }

    public static Action forRootItem(Map.Entry<DexItem, ProguardKeepRule> root) {
      DexItem item = root.getKey();
      KeepReason reason = KeepReason.dueToKeepRule(root.getValue());
      if (item instanceof DexClass) {
        return markInstantiated((DexClass) item, reason);
      } else if (item instanceof DexEncodedField) {
        return markFieldKept((DexEncodedField) item, reason);
      } else if (item instanceof DexEncodedMethod) {
        return markMethodKept((DexEncodedMethod) item, reason);
      } else {
        throw new IllegalArgumentException(item.toString());
      }
    }

    private enum Kind {
      MARK_REACHABLE_VIRTUAL,
      MARK_REACHABLE_INTERFACE,
      MARK_REACHABLE_SUPER,
      MARK_REACHABLE_FIELD,
      MARK_INSTANTIATED,
      MARK_METHOD_LIVE,
      MARK_METHOD_KEPT,
      MARK_FIELD_KEPT
    }
  }

  /**
   * Encapsulates liveness and reachability information for an application.
   */
  public static class AppInfoWithLiveness extends AppInfoWithSubtyping {

    /**
     * Set of types that are mentioned in the program. We at least need an empty abstract classitem
     * for these.
     */
    public final SortedSet<DexType> liveTypes;
    /**
     * Set of types that are actually instantiated. These cannot be abstract.
     */
    final SortedSet<DexType> instantiatedTypes;
    /**
     * Set of methods that are the immediate target of an invoke. They might not actually be live
     * but are required so that invokes can find the method. If such a method is not live (i.e. not
     * contained in {@link #liveMethods}, it may be marked as abstract and its implementation may be
     * removed.
     */
    final SortedSet<DexMethod> targetedMethods;
    /**
     * Set of methods that belong to live classes and can be reached by invokes. These need to be
     * kept.
     */
    final SortedSet<DexMethod> liveMethods;
    /**
     * Set of fields that belong to live classes and can be reached by invokes. These need to be
     * kept.
     */
    public final SortedSet<DexField> liveFields;
    /**
     * Set of all fields which may be touched by a get operation. This is actual field definitions.
     */
    public final SortedSet<DexField> fieldsRead;
    /**
     * Set of all fields which may be touched by a put operation. This is actual field definitions.
     */
    public final SortedSet<DexField> fieldsWritten;
    /**
     * Set of all field ids used in instance field reads.
     */
    public final SortedSet<DexField> instanceFieldReads;
    /**
     * Set of all field ids used in instance field writes.
     */
    public final SortedSet<DexField> instanceFieldWrites;
    /**
     * Set of all field ids used in static static field reads.
     */
    public final SortedSet<DexField> staticFieldReads;
    /**
     * Set of all field ids used in static field writes.
     */
    public final SortedSet<DexField> staticFieldWrites;
    /**
     * Set of all methods referenced in virtual invokes;
     */
    public final SortedSet<DexMethod> virtualInvokes;
    /**
     * Set of all methods referenced in super invokes;
     */
    public final SortedSet<DexMethod> superInvokes;
    /**
     * Set of all methods referenced in direct invokes;
     */
    public final SortedSet<DexMethod> directInvokes;
    /**
     * Set of all methods referenced in static invokes;
     */
    public final SortedSet<DexMethod> staticInvokes;
    /**
     * Set of all items that have to be kept independent of whether they are used.
     */
    final Set<DexItem> pinnedItems;
    /**
     * All items with assumenosideeffects rule.
     */
    public final Map<DexItem, ProguardMemberRule> noSideEffects;
    /**
     * All items with assumevalues rule.
     */
    public final Map<DexItem, ProguardMemberRule> assumedValues;
    /**
     * All methods that have to be inlined due to a configuration directive.
     */
    public final Set<DexItem> alwaysInline;
    /**
     * Map from the class of an extension to the state it produced.
     */
    final Map<Class<?>, Object> extensions;
    /**
     * A set of types that have been removed by the {@link TreePruner}.
     */
    final Set<DexType> prunedTypes;

    private AppInfoWithLiveness(AppInfoWithSubtyping appInfo, Enqueuer enqueuer) {
      super(appInfo);
      this.liveTypes = ImmutableSortedSet.copyOf(
          PresortedComparable<DexType>::slowCompareTo, enqueuer.liveTypes);
      this.instantiatedTypes = ImmutableSortedSet.copyOf(
          PresortedComparable<DexType>::slowCompareTo, enqueuer.instantiatedTypes.getItems());
      this.targetedMethods = toSortedDescriptorSet(enqueuer.targetedMethods.getItems());
      this.liveMethods = toSortedDescriptorSet(enqueuer.liveMethods.getItems());
      this.liveFields = toSortedDescriptorSet(enqueuer.liveFields.getItems());
      this.instanceFieldReads = enqueuer.collectInstanceFieldsRead();
      this.instanceFieldWrites = enqueuer.collectInstanceFieldsWritten();
      this.staticFieldReads = enqueuer.collectStaticFieldsRead();
      this.staticFieldWrites = enqueuer.collectStaticFieldsWritten();
      this.fieldsRead = enqueuer.collectFieldsRead();
      this.fieldsWritten = enqueuer.collectFieldsWritten();
      this.pinnedItems = rewritePinnedItemsToDescriptors(enqueuer.pinnedItems);
      this.virtualInvokes = joinInvokedMethods(enqueuer.virtualInvokes);
      this.superInvokes = joinInvokedMethods(enqueuer.superInvokes);
      this.directInvokes = joinInvokedMethods(enqueuer.directInvokes);
      this.staticInvokes = joinInvokedMethods(enqueuer.staticInvokes);
      this.noSideEffects = enqueuer.rootSet.noSideEffects;
      this.assumedValues = enqueuer.rootSet.assumedValues;
      this.alwaysInline = enqueuer.rootSet.alwaysInline;
      this.extensions = enqueuer.extensionsState;
      this.prunedTypes = Collections.emptySet();
      assert Sets.intersection(instanceFieldReads, staticFieldReads).size() == 0;
      assert Sets.intersection(instanceFieldWrites, staticFieldWrites).size() == 0;
    }

    private AppInfoWithLiveness(AppInfoWithLiveness previous, DexApplication application,
        Collection<DexType> removedClasses) {
      super(application);
      this.liveTypes = previous.liveTypes;
      this.instantiatedTypes = previous.instantiatedTypes;
      this.targetedMethods = previous.targetedMethods;
      this.liveMethods = previous.liveMethods;
      this.liveFields = previous.liveFields;
      this.instanceFieldReads = previous.instanceFieldReads;
      this.instanceFieldWrites = previous.instanceFieldWrites;
      this.staticFieldReads = previous.staticFieldReads;
      this.staticFieldWrites = previous.staticFieldWrites;
      this.fieldsRead = previous.fieldsRead;
      // TODO(herhut): We remove fields that are only written, so maybe update this.
      this.fieldsWritten = previous.fieldsWritten;
      assert assertNoItemRemoved(previous.pinnedItems, removedClasses);
      this.pinnedItems = previous.pinnedItems;
      this.noSideEffects = previous.noSideEffects;
      this.assumedValues = previous.assumedValues;
      this.virtualInvokes = previous.virtualInvokes;
      this.superInvokes = previous.superInvokes;
      this.directInvokes = previous.directInvokes;
      this.staticInvokes = previous.staticInvokes;
      this.extensions = previous.extensions;
      this.alwaysInline = previous.alwaysInline;
      this.prunedTypes = mergeSets(previous.prunedTypes, removedClasses);
      assert Sets.intersection(instanceFieldReads, staticFieldReads).size() == 0;
      assert Sets.intersection(instanceFieldWrites, staticFieldWrites).size() == 0;
    }

    private AppInfoWithLiveness(AppInfoWithLiveness previous,
        DirectMappedDexApplication application,
        GraphLense lense) {
      super(application, lense);
      this.liveTypes = rewriteItems(previous.liveTypes, lense::lookupType);
      this.instantiatedTypes = rewriteItems(previous.instantiatedTypes, lense::lookupType);
      this.targetedMethods = rewriteItems(previous.targetedMethods, lense::lookupMethod);
      this.liveMethods = rewriteItems(previous.liveMethods, lense::lookupMethod);
      this.liveFields = rewriteItems(previous.liveFields, lense::lookupField);
      this.instanceFieldReads = rewriteItems(previous.instanceFieldReads, lense::lookupField);
      this.instanceFieldWrites = rewriteItems(previous.instanceFieldWrites, lense::lookupField);
      this.staticFieldReads = rewriteItems(previous.staticFieldReads, lense::lookupField);
      this.staticFieldWrites = rewriteItems(previous.staticFieldWrites, lense::lookupField);
      this.fieldsRead = rewriteItems(previous.fieldsRead, lense::lookupField);
      this.fieldsWritten = rewriteItems(previous.fieldsWritten, lense::lookupField);
      this.pinnedItems = rewriteMixedItems(previous.pinnedItems, lense);
      this.virtualInvokes = rewriteItems(previous.virtualInvokes, lense::lookupMethod);
      this.superInvokes = rewriteItems(previous.superInvokes, lense::lookupMethod);
      this.directInvokes = rewriteItems(previous.directInvokes, lense::lookupMethod);
      this.staticInvokes = rewriteItems(previous.staticInvokes, lense::lookupMethod);
      this.prunedTypes = rewriteItems(previous.prunedTypes, lense::lookupType);
      // TODO(herhut): Migrate these to Descriptors, as well.
      assert assertNotModifiedByLense(previous.noSideEffects.keySet(), lense);
      this.noSideEffects = previous.noSideEffects;
      assert assertNotModifiedByLense(previous.assumedValues.keySet(), lense);
      this.assumedValues = previous.assumedValues;
      assert assertNotModifiedByLense(previous.alwaysInline, lense);
      this.alwaysInline = previous.alwaysInline;
      this.extensions = previous.extensions;
      // Sanity check sets after rewriting.
      assert Sets.intersection(instanceFieldReads, staticFieldReads).isEmpty();
      assert Sets.intersection(instanceFieldWrites, staticFieldWrites).isEmpty();
    }

    private boolean assertNoItemRemoved(Collection<DexItem> items, Collection<DexType> types) {
      Set<DexType> typeSet = ImmutableSet.copyOf(types);
      for (DexItem item : items) {
        if (item instanceof DexType) {
          assert !typeSet.contains(item);
        } else if (item instanceof DexMethod) {
          assert !typeSet.contains(((DexMethod) item).getHolder());
        } else if (item instanceof DexField) {
          assert !typeSet.contains(((DexField) item).getHolder());
        } else {
          assert false;
        }
      }
      return true;
    }

    private boolean assertNotModifiedByLense(Iterable<DexItem> items, GraphLense lense) {
      for (DexItem item : items) {
        if (item instanceof DexClass) {
          DexType type = ((DexClass) item).type;
          assert lense.lookupType(type, null) == type;
        } else if (item instanceof DexEncodedMethod) {
          DexEncodedMethod method = (DexEncodedMethod) item;
          // We only allow changes to bridge methods, as these get retargeted even if they
          // are kept.
          assert method.accessFlags.isBridge()
              || lense.lookupMethod(method.method, null) == method.method;
        } else if (item instanceof DexEncodedField) {
          DexField field = ((DexEncodedField) item).field;
          assert lense.lookupField(field, null) == field;
        } else {
          assert false;
        }
      }
      return true;
    }

    private SortedSet<DexMethod> joinInvokedMethods(Map<DexType, Set<DexMethod>> invokes) {
      ImmutableSortedSet.Builder<DexMethod> builder =
          new ImmutableSortedSet.Builder<>(PresortedComparable::slowCompare);
      invokes.values().forEach(builder::addAll);
      return builder.build();
    }

    private <T extends PresortedComparable<T>> SortedSet<T> toSortedDescriptorSet(
        Set<? extends KeyedDexItem<T>> set) {
      ImmutableSortedSet.Builder<T> builder =
          new ImmutableSortedSet.Builder<>(PresortedComparable<T>::slowCompareTo);
      for (KeyedDexItem<T> item : set) {
        builder.add(item.getKey());
      }
      return builder.build();
    }

    private ImmutableSet<DexItem> rewritePinnedItemsToDescriptors(Collection<DexItem> source) {
      ImmutableSet.Builder<DexItem> builder = ImmutableSet.builder();
      for (DexItem item : source) {
        // TODO(67934123) There should be a common interface to extract this information.
        if (item instanceof DexClass) {
          builder.add(((DexClass) item).type);
        } else if (item instanceof DexEncodedMethod) {
          builder.add(((DexEncodedMethod) item).method);
        } else if (item instanceof DexEncodedField) {
          builder.add(((DexEncodedField) item).field);
        } else {
          throw new Unreachable();
        }
      }
      return builder.build();
    }

    private static <T extends PresortedComparable<T>> ImmutableSortedSet<T> rewriteItems(
        Set<T> original, BiFunction<T, DexEncodedMethod, T> rewrite) {
      ImmutableSortedSet.Builder<T> builder =
          new ImmutableSortedSet.Builder<>(PresortedComparable::slowCompare);
      for (T item : original) {
        builder.add(rewrite.apply(item, null));
      }
      return builder.build();
    }

    private static ImmutableSet<DexItem> rewriteMixedItems(
        Set<DexItem> original, GraphLense lense) {
      ImmutableSet.Builder<DexItem> builder = ImmutableSet.builder();
      for (DexItem item : original) {
        // TODO(67934123) There should be a common interface to perform the dispatch.
        if (item instanceof DexType) {
          builder.add(lense.lookupType((DexType) item, null));
        } else if (item instanceof DexMethod) {
          builder.add(lense.lookupMethod((DexMethod) item, null));
        } else if (item instanceof DexField) {
          builder.add(lense.lookupField((DexField) item, null));
        } else {
          throw new Unreachable();
        }
      }
      return builder.build();
    }

    private static <T> Set<T> mergeSets(Collection<T> first, Collection<T> second) {
      ImmutableSet.Builder<T> builder = ImmutableSet.builder();
      builder.addAll(first);
      builder.addAll(second);
      return builder.build();
    }

    @SuppressWarnings("unchecked")
    public <T> T getExtension(Class<?> extension, T defaultValue) {
      if (extensions.containsKey(extension)) {
        return (T) extensions.get(extension);
      } else {
        return defaultValue;
      }
    }

    public <T> void setExtension(Class<?> extension, T value) {
      assert !extensions.containsKey(extension);
      extensions.put(extension, value);
    }

    @Override
    public boolean hasLiveness() {
      return true;
    }

    @Override
    public AppInfoWithLiveness withLiveness() {
      return this;
    }

    // TODO(67934123) Unify into one method,
    public boolean isPinned(DexType item) {
      return pinnedItems.contains(item);
    }

    // TODO(67934123) Unify into one method,
    public boolean isPinned(DexMethod item) {
      return pinnedItems.contains(item);
    }

    // TODO(67934123) Unify into one method,
    public boolean isPinned(DexField item) {
      return pinnedItems.contains(item);
    }


    public Iterable<DexItem> getPinnedItems() {
      return pinnedItems;
    }

    /**
     * Returns a copy of this AppInfoWithLiveness where the set of classes is pruned using the
     * given DexApplication object.
     */
    public AppInfoWithLiveness prunedCopyFrom(DexApplication application,
        Collection<DexType> removedClasses) {
      return new AppInfoWithLiveness(this, application, removedClasses);
    }

    public AppInfoWithLiveness rewrittenWithLense(DirectMappedDexApplication application,
        GraphLense lense) {
      assert lense.isContextFree();
      return new AppInfoWithLiveness(this, application, lense);
    }

    /**
     * Returns true if the given type was part of the original program but has been removed during
     * tree shaking.
     */
    public boolean wasPruned(DexType type) {
      return prunedTypes.contains(type);
    }
  }

  private static class SetWithReason<T> {

    private final Set<T> items = Sets.newIdentityHashSet();
    private final Map<T, KeepReason> reasons = Maps.newIdentityHashMap();

    boolean add(T item, KeepReason reason) {
      if (items.add(item)) {
        reasons.put(item, reason);
        return true;
      }
      return false;
    }

    boolean contains(T item) {
      return items.contains(item);
    }

    Set<T> getItems() {
      return ImmutableSet.copyOf(items);
    }

    Map<T, KeepReason> getReasons() {
      return ImmutableMap.copyOf(reasons);
    }
  }

  private class AnnotationReferenceMarker implements IndexedItemCollection {

    private final DexItem annotationHolder;
    private final DexItemFactory dexItemFactory;

    private AnnotationReferenceMarker(DexItem annotationHolder, DexItemFactory dexItemFactory) {
      this.annotationHolder = annotationHolder;
      this.dexItemFactory = dexItemFactory;
    }

    @Override
    public boolean addClass(DexProgramClass dexProgramClass) {
      return false;
    }

    @Override
    public boolean addField(DexField field) {
      DexClass holder = appInfo.definitionFor(field.clazz);
      if (holder == null) {
        return false;
      }
      DexEncodedField target = holder.findStaticTarget(field);
      if (target != null) {
        // There is no dispatch on annotations, so only keep what is directly referenced.
        if (target.field == field) {
          markStaticFieldAsLive(field, KeepReason.referencedInAnnotation(annotationHolder));
        }
      } else {
        target = holder.findInstanceTarget(field);
        // There is no dispatch on annotations, so only keep what is directly referenced.
        if (target != null && target.field != field) {
          markFieldAsReachable(field, KeepReason.referencedInAnnotation(annotationHolder));
        }
      }
      return false;
    }

    @Override
    public boolean addMethod(DexMethod method) {
      DexClass holder = appInfo.definitionFor(method.holder);
      if (holder == null) {
        return false;
      }
      DexEncodedMethod target = holder.findDirectTarget(method);
      if (target != null) {
        // There is no dispatch on annotations, so only keep what is directly referenced.
        if (target.method == method) {
          markDirectStaticOrConstructorMethodAsLive(
              target, KeepReason.referencedInAnnotation(annotationHolder));
        }
      } else {
        target = holder.findVirtualTarget(method);
        // There is no dispatch on annotations, so only keep what is directly referenced.
        if (target != null && target.method == method) {
          markMethodAsTargeted(target, KeepReason.referencedInAnnotation(annotationHolder));
        }
      }
      return false;
    }

    @Override
    public boolean addString(DexString string) {
      return false;
    }

    @Override
    public boolean addProto(DexProto proto) {
      return false;
    }

    @Override
    public boolean addCallSite(DexCallSite callSite) {
      return false;
    }

    @Override
    public boolean addMethodHandle(DexMethodHandle methodHandle) {
      return false;
    }

    @Override
    public boolean addType(DexType type) {
      // Annotations can also contain the void type, which is not a class type, so filter it out
      // here.
      if (type != dexItemFactory.voidType) {
        markTypeAsLive(type);
      }
      return false;
    }
  }

  public interface SemanticsProvider {

    boolean appliesTo(DexEncodedMethod method);

    Object processMethod(DexEncodedMethod method,
        com.android.tools.r8.graph.UseRegistry useRegistry, Object state);
  }
}
