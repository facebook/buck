// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexTypeList;
import com.android.tools.r8.graph.GraphLense;
import com.android.tools.r8.graph.GraphLense.Builder;
import com.android.tools.r8.graph.KeyedDexItem;
import com.android.tools.r8.graph.PresortedComparable;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.optimize.InvokeSingleTargetExtractor;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.android.tools.r8.utils.FieldSignatureEquivalence;
import com.android.tools.r8.utils.MethodSignatureEquivalence;
import com.android.tools.r8.utils.Timing;
import com.google.common.base.Equivalence;
import com.google.common.base.Equivalence.Wrapper;
import com.google.common.collect.Iterators;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Merges Supertypes with a single implementation into their single subtype.
 * <p>
 * A common use-case for this is to merge an interface into its single implementation.
 * <p>
 * The class merger only fixes the structure of the graph but leaves the actual instructions
 * untouched. Fixup of instructions is deferred via a {@link GraphLense} to the Ir building phase.
 */
public class SimpleClassMerger {

  private final DexApplication application;
  private final AppInfoWithLiveness appInfo;
  private final GraphLense graphLense;
  private final GraphLense.Builder renamedMembersLense = GraphLense.builder();
  private final Map<DexType, DexType> mergedClasses = new IdentityHashMap<>();
  private final Timing timing;
  private Collection<DexMethod> invokes;
  private int numberOfMerges = 0;

  public SimpleClassMerger(DexApplication application, AppInfoWithLiveness appInfo,
      GraphLense graphLense, Timing timing) {
    this.application = application;
    this.appInfo = appInfo;
    this.graphLense = graphLense;
    this.timing = timing;
  }

  private boolean isMergeCandidate(DexProgramClass clazz) {
    // We can merge program classes if they are not instantiated, have a single subtype
    // and we do not have to keep them.
    return !clazz.isLibraryClass()
        && !appInfo.instantiatedTypes.contains(clazz.type)
        && !appInfo.isPinned(clazz.type)
        && clazz.type.getSingleSubtype() != null;
  }

  private void addProgramMethods(Set<Wrapper<DexMethod>> set, DexMethod method,
      Equivalence<DexMethod> equivalence) {
    DexClass definition = appInfo.definitionFor(method.holder);
    if (definition != null && definition.isProgramClass()) {
      set.add(equivalence.wrap(method));
    }
  }

  private Collection<DexMethod> getInvokes() {
    if (invokes == null) {
      // Collect all reachable methods that are not within a library class. Those defined on
      // library classes are known not to have program classes in their signature.
      // Also filter methods that only use types from library classes in their signatures. We
      // know that those won't conflict.
      Set<Wrapper<DexMethod>> filteredInvokes = new HashSet<>();
      Equivalence<DexMethod> equivalence = MethodSignatureEquivalence.get();
      appInfo.targetedMethods.forEach(m -> addProgramMethods(filteredInvokes, m, equivalence));
      invokes = filteredInvokes.stream().map(Wrapper::get).filter(this::removeNonProgram)
          .collect(Collectors.toList());
    }
    return invokes;
  }

  private boolean isProgramClass(DexType type) {
    if (type.isArrayType()) {
      type = type.toBaseType(appInfo.dexItemFactory);
    }
    if (type.isClassType()) {
      DexClass clazz = appInfo.definitionFor(type);
      if (clazz != null && clazz.isProgramClass()) {
        return true;
      }
    }
    return false;
  }

  private boolean removeNonProgram(DexMethod dexMethod) {
    for (DexType type : dexMethod.proto.parameters.values) {
      if (isProgramClass(type)) {
        return true;
      }
    }
    return isProgramClass(dexMethod.proto.returnType);
  }

  public GraphLense run() {
    timing.begin("merge");
    GraphLense mergingGraphLense = mergeClasses(graphLense);
    timing.end();
    timing.begin("fixup");
    GraphLense result = new TreeFixer().fixupTypeReferences(mergingGraphLense);
    timing.end();
    return result;
  }

  private GraphLense mergeClasses(GraphLense graphLense) {
    for (DexProgramClass clazz : application.classes()) {
      if (isMergeCandidate(clazz)) {
        DexClass targetClass = appInfo.definitionFor(clazz.type.getSingleSubtype());
        if (appInfo.isPinned(targetClass.type)) {
          // We have to keep the target class intact, so we cannot merge it.
          continue;
        }
        if (mergedClasses.containsKey(targetClass.type)) {
          // TODO(herhut): Traverse top-down.
          continue;
        }
        if (clazz.hasClassInitializer() && targetClass.hasClassInitializer()) {
          // TODO(herhut): Handle class initializers.
          if (Log.ENABLED) {
            Log.info(getClass(), "Cannot merge %s into %s due to static initializers.",
                clazz.toSourceString(), targetClass.toSourceString());
          }
          continue;
        }
        // Guard against the case where we have two methods that may get the same signature
        // if we replace types. This is rare, so we approximate and err on the safe side here.
        if (new CollisionDetector(clazz.type, targetClass.type, getInvokes(), mergedClasses)
            .mayCollide()) {
          if (Log.ENABLED) {
            Log.info(getClass(), "Cannot merge %s into %s due to conflict.", clazz.toSourceString(),
                targetClass.toSourceString());
          }
          continue;
        }
        boolean merged = new ClassMerger(clazz, targetClass).merge();
        if (Log.ENABLED) {
          if (merged) {
            numberOfMerges++;
            Log.info(getClass(), "Merged class %s into %s.", clazz.toSourceString(),
                targetClass.toSourceString());
          } else {
            Log.info(getClass(), "Aborted merge for class %s into %s.",
                clazz.toSourceString(), targetClass.toSourceString());
          }
        }
      }
    }
    if (Log.ENABLED) {
      Log.debug(getClass(), "Merged %d classes.", numberOfMerges);
    }
    return renamedMembersLense.build(application.dexItemFactory, graphLense);
  }

  private class ClassMerger {

    private static final String CONSTRUCTOR_NAME = "constructor";

    private final DexClass source;
    private final DexClass target;
    private final Map<DexEncodedMethod, DexEncodedMethod> deferredRenamings = new HashMap<>();
    private boolean abortMerge = false;

    private ClassMerger(DexClass source, DexClass target) {
      this.source = source;
      this.target = target;
    }

    public boolean merge() {
      // Merge the class [clazz] into [targetClass] by adding all methods to
      // targetClass that are not currently contained.
      // Step 1: Merge methods
      Set<Wrapper<DexMethod>> existingMethods = new HashSet<>();
      addAll(existingMethods, target.directMethods(), MethodSignatureEquivalence.get());
      addAll(existingMethods, target.virtualMethods(), MethodSignatureEquivalence.get());
      Collection<DexEncodedMethod> mergedDirectMethods = mergeItems(
          Iterators.transform(Iterators.forArray(source.directMethods()), this::renameConstructors),
          target.directMethods(),
          MethodSignatureEquivalence.get(),
          existingMethods,
          this::renameMethod
      );
      Iterator<DexEncodedMethod> methods = Iterators.forArray(source.virtualMethods());
      if (source.accessFlags.isInterface()) {
        // If merging an interface, only merge methods that are not otherwise defined in the
        // target class.
        methods = Iterators.transform(methods, this::filterShadowedInterfaceMethods);
      }
      Collection<DexEncodedMethod> mergedVirtualMethods = mergeItems(
          methods,
          target.virtualMethods(),
          MethodSignatureEquivalence.get(),
          existingMethods,
          this::abortOnNonAbstract);
      if (abortMerge) {
        return false;
      }
      // Step 2: Merge fields
      Set<Wrapper<DexField>> existingFields = new HashSet<>();
      addAll(existingFields, target.instanceFields(), FieldSignatureEquivalence.get());
      addAll(existingFields, target.staticFields(), FieldSignatureEquivalence.get());
      Collection<DexEncodedField> mergedStaticFields = mergeItems(
          Iterators.forArray(source.staticFields()),
          target.staticFields(),
          FieldSignatureEquivalence.get(),
          existingFields,
          this::renameField);
      Collection<DexEncodedField> mergedInstanceFields = mergeItems(
          Iterators.forArray(source.instanceFields()),
          target.instanceFields(),
          FieldSignatureEquivalence.get(),
          existingFields,
          this::renameField);
      // Step 3: Merge interfaces
      Set<DexType> interfaces = mergeArrays(target.interfaces.values, source.interfaces.values);
      // Now destructively update the class.
      // Step 1: Update supertype or fix interfaces.
      if (source.isInterface()) {
        interfaces.remove(source.type);
      } else {
        assert !target.isInterface();
        target.superType = source.superType;
      }
      target.interfaces = interfaces.isEmpty()
          ? DexTypeList.empty()
          : new DexTypeList(interfaces.toArray(new DexType[interfaces.size()]));
      // Step 2: replace fields and methods.
      target.setDirectMethods(mergedDirectMethods
          .toArray(new DexEncodedMethod[mergedDirectMethods.size()]));
      target.setVirtualMethods(mergedVirtualMethods
          .toArray(new DexEncodedMethod[mergedVirtualMethods.size()]));
      target.setStaticFields(mergedStaticFields
          .toArray(new DexEncodedField[mergedStaticFields.size()]));
      target.setInstanceFields(mergedInstanceFields
          .toArray(new DexEncodedField[mergedInstanceFields.size()]));
      // Step 3: Unlink old class to ease tree shaking.
      source.superType = application.dexItemFactory.objectType;
      source.setDirectMethods(null);
      source.setVirtualMethods(null);
      source.setInstanceFields(null);
      source.setStaticFields(null);
      source.interfaces = DexTypeList.empty();
      // Step 4: Record merging.
      mergedClasses.put(source.type, target.type);
      // Step 5: Make deferred renamings final.
      deferredRenamings.forEach((from, to) -> renamedMembersLense.map(from.method, to.method));
      return true;
    }

    private DexEncodedMethod filterShadowedInterfaceMethods(DexEncodedMethod m) {
      DexEncodedMethod actual = appInfo.lookupVirtualDefinition(target.type, m.method);
      assert actual != null;
      if (actual != m) {
        // We will drop a method here, so record it as a potential renaming.
        deferredRenamings.put(m, actual);
        return null;
      }
      // We will keep the method, so the class better be abstract.
      assert target.accessFlags.isAbstract();
      return m;
    }

    private <T extends KeyedDexItem<S>, S extends PresortedComparable<S>> void addAll(
        Collection<Wrapper<S>> collection, T[] items, Equivalence<S> equivalence) {
      for (T item : items) {
        collection.add(equivalence.wrap(item.getKey()));
      }
    }

    private <T> Set<T> mergeArrays(T[] one, T[] other) {
      Set<T> merged = new LinkedHashSet<>();
      Collections.addAll(merged, one);
      Collections.addAll(merged, other);
      return merged;
    }

    private <T extends PresortedComparable<T>, S extends KeyedDexItem<T>> Collection<S> mergeItems(
        Iterator<S> fromItems,
        S[] toItems,
        Equivalence<T> equivalence,
        Set<Wrapper<T>> existing,
        BiFunction<S, S, S> onConflict) {
      HashMap<Wrapper<T>, S> methods = new HashMap<>();
      // First add everything from the target class. These items are not preprocessed.
      for (S item : toItems) {
        methods.put(equivalence.wrap(item.getKey()), item);
      }
      // Now add the new methods, resolving shadowing.
      addNonShadowed(fromItems, methods, equivalence, existing, onConflict);
      return methods.values();
    }

    private <T extends PresortedComparable<T>, S extends KeyedDexItem<T>> void addNonShadowed(
        Iterator<S> items,
        HashMap<Wrapper<T>, S> map,
        Equivalence<T> equivalence,
        Set<Wrapper<T>> existing,
        BiFunction<S, S, S> onConflict) {
      while (items.hasNext()) {
        S item = items.next();
        if (item == null) {
          // This item was filtered out by a preprocessing.
          continue;
        }
        Wrapper<T> wrapped = equivalence.wrap(item.getKey());
        if (existing.contains(wrapped)) {
          S resolved = onConflict.apply(map.get(wrapped), item);
          wrapped = equivalence.wrap(resolved.getKey());
          map.put(wrapped, resolved);
        } else {
          map.put(wrapped, item);
        }
      }
    }

    private DexString makeMergedName(String nameString, DexType holder) {
      return application.dexItemFactory
          .createString(nameString + "$" + holder.toSourceString().replace('.', '$'));
    }

    private DexEncodedMethod abortOnNonAbstract(DexEncodedMethod existing,
        DexEncodedMethod method) {
      if (existing == null) {
        // This is a conflict between a static and virtual method. Abort.
        abortMerge = true;
        return method;
      }
      // Ignore if we merge in an abstract method or if we override a bridge method that would
      // bridge to the superclasses method.
      if (method.accessFlags.isAbstract()) {
        // We make a method disappear here, so record the renaming so that calls to the previous
        // target get forwarded properly.
        deferredRenamings.put(method, existing);
        return existing;
      } else if (existing.accessFlags.isBridge()) {
        InvokeSingleTargetExtractor extractor = new InvokeSingleTargetExtractor();
        existing.getCode().registerReachableDefinitions(extractor);
        if (extractor.getTarget() != method.method) {
          abortMerge = true;
        }
        return method;
      } else {
        abortMerge = true;
        return existing;
      }
    }

    private DexEncodedMethod renameConstructors(DexEncodedMethod method) {
      // Only rename instance initializers.
      if (!method.isInstanceInitializer()) {
        return method;
      }
      DexType holder = method.method.holder;
      DexEncodedMethod result = method
          .toRenamedMethod(makeMergedName(CONSTRUCTOR_NAME, holder), application.dexItemFactory);
      result.markForceInline();
      deferredRenamings.put(method, result);
      // Renamed constructors turn into ordinary private functions. They can be private, as
      // they are only references from their direct subclass, which they were merged into.
      result.accessFlags.unsetConstructor();
      result.accessFlags.unsetPublic();
      result.accessFlags.unsetProtected();
      result.accessFlags.setPrivate();
      return result;
    }

    private DexEncodedMethod renameMethod(DexEncodedMethod existing, DexEncodedMethod method) {
      // We cannot handle renaming static initializers yet and constructors should have been
      // renamed already.
      assert !method.accessFlags.isConstructor();
      DexType holder = method.method.holder;
      String name = method.method.name.toSourceString();
      DexEncodedMethod result = method
          .toRenamedMethod(makeMergedName(name, holder), application.dexItemFactory);
      renamedMembersLense.map(method.method, result.method);
      return result;
    }

    private DexEncodedField renameField(DexEncodedField existing, DexEncodedField field) {
      DexString oldName = field.field.name;
      DexType holder = field.field.clazz;
      DexEncodedField result = field
          .toRenamedField(makeMergedName(oldName.toSourceString(), holder),
              application.dexItemFactory);
      renamedMembersLense.map(field.field, result.field);
      return result;
    }
  }

  private class TreeFixer {

    private final Builder lense = GraphLense.builder();
    Map<DexProto, DexProto> protoFixupCache = new IdentityHashMap<>();

    private GraphLense fixupTypeReferences(GraphLense graphLense) {
      // Globally substitute merged class types in protos and holders.
      for (DexProgramClass clazz : appInfo.classes()) {
        clazz.setDirectMethods(substituteTypesIn(clazz.directMethods()));
        clazz.setVirtualMethods(substituteTypesIn(clazz.virtualMethods()));
        clazz.setVirtualMethods(removeDupes(clazz.virtualMethods()));
        clazz.setStaticFields(substituteTypesIn(clazz.staticFields()));
        clazz.setInstanceFields(substituteTypesIn(clazz.instanceFields()));
      }
      // Record type renamings so instanceof and checkcast checks are also fixed.
      for (DexType type : mergedClasses.keySet()) {
        DexType fixed = fixupType(type);
        lense.map(type, fixed);
      }
      return lense.build(application.dexItemFactory, graphLense);
    }

    private DexEncodedMethod[] removeDupes(DexEncodedMethod[] methods) {
      if (methods == null) {
        return null;
      }
      Map<DexMethod, DexEncodedMethod> filtered = new IdentityHashMap<>();
      for (DexEncodedMethod method : methods) {
        DexEncodedMethod previous = filtered.put(method.method, method);
        if (previous != null) {
          if (!previous.accessFlags.isBridge()) {
            if (!method.accessFlags.isBridge()) {
              throw new CompilationError(
                  "Class merging produced invalid result on: " + previous.toSourceString());
            } else {
              filtered.put(previous.method, previous);
            }
          }
        }
      }
      if (filtered.size() == methods.length) {
        return methods;
      }
      return filtered.values().toArray(new DexEncodedMethod[filtered.size()]);
    }

    private DexEncodedMethod[] substituteTypesIn(DexEncodedMethod[] methods) {
      if (methods == null) {
        return null;
      }
      for (int i = 0; i < methods.length; i++) {
        DexEncodedMethod encodedMethod = methods[i];
        DexMethod method = encodedMethod.method;
        DexProto newProto = getUpdatedProto(method.proto);
        DexType newHolder = fixupType(method.holder);
        DexMethod newMethod = application.dexItemFactory.createMethod(newHolder, newProto,
            method.name);
        if (newMethod != encodedMethod.method) {
          lense.map(encodedMethod.method, newMethod);
          methods[i] = encodedMethod.toTypeSubstitutedMethod(newMethod);
        }
      }
      return methods;
    }

    private DexEncodedField[] substituteTypesIn(DexEncodedField[] fields) {
      if (fields == null) {
        return null;
      }
      for (int i = 0; i < fields.length; i++) {
        DexEncodedField encodedField = fields[i];
        DexField field = encodedField.field;
        DexType newType = fixupType(field.type);
        DexType newHolder = fixupType(field.clazz);
        DexField newField = application.dexItemFactory.createField(newHolder, newType, field.name);
        if (newField != encodedField.field) {
          lense.map(encodedField.field, newField);
          fields[i] = encodedField.toTypeSubstitutedField(newField);
        }
      }
      return fields;
    }

    private DexProto getUpdatedProto(DexProto proto) {
      DexProto result = protoFixupCache.get(proto);
      if (result == null) {
        DexType returnType = fixupType(proto.returnType);
        DexType[] arguments = fixupTypes(proto.parameters.values);
        result = application.dexItemFactory.createProto(returnType, arguments);
        protoFixupCache.put(proto, result);
      }
      return result;
    }

    private DexType fixupType(DexType type) {
      if (type.isArrayType()) {
        DexType base = type.toBaseType(application.dexItemFactory);
        DexType fixed = fixupType(base);
        if (base == fixed) {
          return type;
        } else {
          return type.replaceBaseType(fixed, application.dexItemFactory);
        }
      }
      while (mergedClasses.containsKey(type)) {
        type = mergedClasses.get(type);
      }
      return type;
    }

    private DexType[] fixupTypes(DexType[] types) {
      DexType[] result = new DexType[types.length];
      for (int i = 0; i < result.length; i++) {
        result[i] = fixupType(types[i]);
      }
      return result;
    }
  }

  private static class CollisionDetector {

    private static int NOT_FOUND = 1 << (Integer.SIZE - 1);

    // TODO(herhut): Maybe cache seenPositions for target classes.
    private final Map<DexString, Int2IntMap> seenPositions = new IdentityHashMap<>();
    private final Reference2IntMap<DexProto> targetProtoCache;
    private final Reference2IntMap<DexProto> sourceProtoCache;
    private final DexType source, target;
    private final Collection<DexMethod> invokes;
    private final Map<DexType, DexType> substituions;

    private CollisionDetector(DexType source, DexType target, Collection<DexMethod> invokes,
        Map<DexType, DexType> substitutions) {
      this.source = source;
      this.target = target;
      this.invokes = invokes;
      this.substituions = substitutions;
      this.targetProtoCache = new Reference2IntOpenHashMap<>(invokes.size() / 2);
      this.targetProtoCache.defaultReturnValue(NOT_FOUND);
      this.sourceProtoCache = new Reference2IntOpenHashMap<>(invokes.size() / 2);
      this.sourceProtoCache.defaultReturnValue(NOT_FOUND);
    }

    boolean mayCollide() {
      fillSeenPositions(invokes);
      // If the type is not used in methods at all, there cannot be any conflict.
      if (seenPositions.isEmpty()) {
        return false;
      }
      for (DexMethod method : invokes) {
        Int2IntMap positionsMap = seenPositions.get(method.name);
        if (positionsMap != null) {
          int arity = method.getArity();
          int previous = positionsMap.get(arity);
          if (previous != NOT_FOUND) {
            assert previous != 0;
            int positions = computePositionsFor(method.proto, source, sourceProtoCache,
                substituions);
            if ((positions & previous) != 0) {
              return true;
            }
          }
        }
      }
      return false;
    }

    private void fillSeenPositions(Collection<DexMethod> invokes) {
      for (DexMethod method : invokes) {
        DexType[] parameters = method.proto.parameters.values;
        int arity = parameters.length;
        int positions = computePositionsFor(method.proto, target, targetProtoCache, substituions);
        if (positions != 0) {
          Int2IntMap positionsMap =
              seenPositions.computeIfAbsent(method.name, k -> {
                Int2IntMap result = new Int2IntOpenHashMap();
                result.defaultReturnValue(NOT_FOUND);
                return result;
              });
          int value = 0;
          int previous = positionsMap.get(arity);
          if (previous != NOT_FOUND) {
            value = previous;
          }
          value |= positions;
          positionsMap.put(arity, value);
        }
      }

    }

    private int computePositionsFor(DexProto proto, DexType type,
        Reference2IntMap<DexProto> cache, Map<DexType, DexType> substitutions) {
      int result = cache.getInt(proto);
      if (result != NOT_FOUND) {
        return result;
      }
      result = 0;
      int bitsUsed = 0;
      int accumulator = 0;
      for (DexType aType : proto.parameters.values) {
        if (substitutions != null) {
          // Substitute the type with the already merged class to estimate what it will
          // look like.
          while (substitutions.containsKey(aType)) {
            aType = substitutions.get(aType);
          }
        }
        accumulator <<= 1;
        bitsUsed++;
        if (aType == type) {
          accumulator |= 1;
        }
        // Handle overflow on 31 bit boundary.
        if (bitsUsed == Integer.SIZE - 1) {
          result |= accumulator;
          accumulator = 0;
          bitsUsed = 0;
        }
      }
      // We also take the return type into account for potential conflicts.
      DexType returnType = proto.returnType;
      if (substitutions != null) {
        while (substitutions.containsKey(returnType)) {
          returnType = substitutions.get(returnType);
        }
      }
      accumulator <<= 1;
      if (returnType == type) {
        accumulator |= 1;
      }
      result |= accumulator;
      cache.put(proto, result);
      return result;
    }
  }

  public Collection<DexType> getRemovedClasses() {
    return Collections.unmodifiableCollection(mergedClasses.keySet());
  }
}
