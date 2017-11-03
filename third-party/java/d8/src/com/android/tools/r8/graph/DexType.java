// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.naming.NamingLens;
import com.android.tools.r8.utils.DescriptorUtils;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

public class DexType extends IndexedDexItem implements PresortedComparable<DexType> {

  private final static int ROOT_LEVEL = 0;
  private final static int UNKNOWN_LEVEL = -1;
  private final static int INTERFACE_LEVEL = -2;

  // Since most Java types has no sub-types, we can just share an empty immutable set until we need
  // to add to it.
  private final static Set<DexType> NO_DIRECT_SUBTYPE = ImmutableSet.of();

  public final DexString descriptor;
  private String toStringCache = null;
  private int hierarchyLevel = UNKNOWN_LEVEL;
  private Set<DexType> directSubtypes = NO_DIRECT_SUBTYPE;

  DexType(DexString descriptor) {
    assert !descriptor.toString().contains(".");
    this.descriptor = descriptor;
  }

  @Override
  public int computeHashCode() {
    return descriptor.hashCode();
  }

  @Override
  public boolean computeEquals(Object other) {
    if (other instanceof DexType) {
      return descriptor.equals(((DexType) other).descriptor);
    }
    return false;
  }

  private void ensureDirectSubTypeSet() {
    if (directSubtypes == NO_DIRECT_SUBTYPE) {
      directSubtypes = Sets.newIdentityHashSet();
    }
  }

  private void setLevel(int level) {
    if (level == hierarchyLevel) {
      return;
    }
    if (hierarchyLevel == INTERFACE_LEVEL) {
      assert level == ROOT_LEVEL + 1;
    } else if (level == INTERFACE_LEVEL) {
      assert hierarchyLevel == ROOT_LEVEL + 1 || hierarchyLevel == UNKNOWN_LEVEL;
      hierarchyLevel = INTERFACE_LEVEL;
    } else {
      assert hierarchyLevel == UNKNOWN_LEVEL;
      hierarchyLevel = level;
    }
  }

  public void addDirectSubtype(DexType type) {
    assert hierarchyLevel != UNKNOWN_LEVEL;
    ensureDirectSubTypeSet();
    directSubtypes.add(type);
    type.setLevel(hierarchyLevel + 1);
  }

  public void tagAsSubtypeRoot() {
    setLevel(ROOT_LEVEL);
  }

  public boolean isInterface() {
    assert isClassType() && hierarchyLevel != UNKNOWN_LEVEL;
    return hierarchyLevel == INTERFACE_LEVEL;
  }

  public void addInterfaceSubtype(DexType type) {
    // Interfaces all inherit from java.lang.Object. However, we assign a special level to
    // identify them later on.
    setLevel(INTERFACE_LEVEL);
    ensureDirectSubTypeSet();
    directSubtypes.add(type);
  }

  static void clearSubtypeInformation(DexType type) {
    type.hierarchyLevel = UNKNOWN_LEVEL;
    type.directSubtypes = NO_DIRECT_SUBTYPE;
  }

  public boolean isSubtypeOf(DexType other, AppInfo appInfo) {
    if (this == other) {
      return true;
    }
    // Treat the object class special as it is always the supertype, even in the case of broken
    // subtype chains.
    if (this == appInfo.dexItemFactory.objectType) {
      return false;
    }
    if (other == appInfo.dexItemFactory.objectType) {
      return true;
    }
    if (this.hierarchyLevel == INTERFACE_LEVEL) {
      return isInterfaceSubtypeOf(this, other, appInfo);
    }
    if (other.hierarchyLevel == INTERFACE_LEVEL) {
      return other.directSubtypes.stream().anyMatch(subtype -> this.isSubtypeOf(subtype,
          appInfo));
    }
    return isSubtypeOfClass(other, appInfo);
  }

  private boolean isInterfaceSubtypeOf(DexType candidate, DexType other, AppInfo appInfo) {
    if (candidate == other || other == appInfo.dexItemFactory.objectType) {
      return true;
    }
    DexClass candidateHolder = appInfo.definitionFor(candidate);
    if (candidateHolder == null) {
      return false;
    }
    for (DexType iface : candidateHolder.interfaces.values) {
      assert iface.hierarchyLevel == INTERFACE_LEVEL;
      if (isInterfaceSubtypeOf(iface, other, appInfo)) {
        return true;
      }
    }
    return false;
  }

  private boolean isSubtypeOfClass(DexType other, AppInfo appInfo) {
    DexType self = this;
    if (other.hierarchyLevel == UNKNOWN_LEVEL) {
      // We have no definition for this class, hence it is not part of the
      // hierarchy.
      return false;
    }
    while (other.hierarchyLevel < self.hierarchyLevel) {
      DexClass holder = appInfo.definitionFor(self);
      assert holder != null && !holder.isInterface();
      self = holder.superType;
    }
    return self == other;
  }

  /**
   * Apply the given function to all classes that directly extend this class.
   *
   * If this class is an interface, then this method will visit all sub-interfaces. This deviates
   * from the dex-file encoding, where subinterfaces "implement" their super interfaces. However,
   * it is consistent with the source language.
   */
  public void forAllExtendsSubtypes(Consumer<DexType> f) {
    assert hierarchyLevel != UNKNOWN_LEVEL;
    if (hierarchyLevel == INTERFACE_LEVEL) {
      for (DexType subtype : directSubtypes) {
        // Other interfaces that extend this interface.
        if (subtype.hierarchyLevel == INTERFACE_LEVEL) {
          f.accept(subtype);
        }
      }
    } else if (hierarchyLevel == ROOT_LEVEL) {
      // This is the object type. Filter out interfaces
      for (DexType subtype : directSubtypes) {
        // Other interfaces that extend this interface.
        if (subtype.hierarchyLevel != INTERFACE_LEVEL) {
          f.accept(subtype);
        }
      }
    } else {
      directSubtypes.forEach(f);
    }
  }

  /**
   * Apply the given function to all classes that directly implement this interface.
   *
   * The implementation does not consider how the hierarchy is encoded in the dex file, where
   * interfaces "implement" their super interfaces. Instead it takes the view of the source
   * language, where interfaces "extend" their superinterface.
   */
  public void forAllImplementsSubtypes(Consumer<DexType> f) {
    if (hierarchyLevel != INTERFACE_LEVEL) {
      return;
    }
    for (DexType subtype : directSubtypes) {
      // Filter out other interfaces.
      if (subtype.hierarchyLevel != INTERFACE_LEVEL) {
        f.accept(subtype);
      }
    }
  }

  public static void forAllInterfaces(DexItemFactory factory, Consumer<DexType> f) {
    DexType object = factory.objectType;
    assert object.hierarchyLevel == ROOT_LEVEL;
    for (DexType subtype : object.directSubtypes) {
      if (subtype.isInterface()) {
        f.accept(subtype);
      }
    }
  }

  public boolean isSamePackage(DexType other) {
    return getPackageDescriptor().equals(other.getPackageDescriptor());
  }

  public String toDescriptorString() {
    return descriptor.toString();
  }

  @Override
  public String toSourceString() {
    if (toStringCache == null) {
      // TODO(ager): Pass in a ProguardMapReader to map names back to original names.
      if (this == DexItemFactory.catchAllType) {
        toStringCache = "CATCH_ALL";
      } else {
        toStringCache = DescriptorUtils.descriptorToJavaType(toDescriptorString());
      }
    }
    return toStringCache;
  }

  public char toShorty() {
    char c = (char) descriptor.content[0];
    return c == '[' ? 'L' : c;
  }

  @Override
  public String toSmaliString() {
    return toDescriptorString();
  }

  @Override
  public String toString() {
    return toSourceString();
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection collection) {
    if (collection.addType(this)) {
      collection.getRenamedDescriptor(this).collectIndexedItems(collection);
    }
  }

  @Override
  public void flushCachedValues() {
    super.flushCachedValues();
    toStringCache = null;
  }

  @Override
  public int getOffset(ObjectToOffsetMapping mapping) {
    return mapping.getOffsetFor(this);
  }

  @Override
  public int compareTo(DexType other) {
    return sortedCompareTo(other.getSortedIndex());
  }

  @Override
  public int slowCompareTo(DexType other) {
    return descriptor.slowCompareTo(other.descriptor);
  }

  @Override
  public int slowCompareTo(DexType other, NamingLens namingLens) {
    DexString thisDescriptor = namingLens.lookupDescriptor(this);
    DexString otherDescriptor = namingLens.lookupDescriptor(other);
    return thisDescriptor.slowCompareTo(otherDescriptor);
  }

  @Override
  public int layeredCompareTo(DexType other, NamingLens namingLens) {
    DexString thisDescriptor = namingLens.lookupDescriptor(this);
    DexString otherDescriptor = namingLens.lookupDescriptor(other);
    return thisDescriptor.compareTo(otherDescriptor);
  }

  public boolean isPrimitiveType() {
    return isPrimitiveType((char) descriptor.content[0]);
  }

  private boolean isPrimitiveType(char c) {
    return c == 'Z' || c == 'B' || c == 'S' || c == 'C' || c == 'I' || c == 'F' || c == 'J'
        || c == 'D';
  }

  public boolean isVoidType() {
    return (char) descriptor.content[0] == 'V';
  }

  public boolean isBooleanType() {
    return descriptor.content[0] == 'Z';
  }

  public boolean isArrayType() {
    char firstChar = (char) descriptor.content[0];
    return firstChar == '[';
  }

  public boolean isClassType() {
    char firstChar = (char) descriptor.content[0];
    return firstChar == 'L';
  }

  public boolean isPrimitiveArrayType() {
    if (!isArrayType()) {
      return false;
    }
    return isPrimitiveType((char) descriptor.content[1]);
  }

  public int elementSizeForPrimitiveArrayType() {
    assert isPrimitiveArrayType();
    switch (descriptor.content[1]) {
      case 'Z':  // boolean
      case 'B':  // byte
        return 1;
      case 'S':  // short
      case 'C':  // char
        return 2;
      case 'I':  // int
      case 'F':  // float
        return 4;
      case 'J':  // long
      case 'D':  // double
        return 8;
      default:
        throw new Unreachable("Not array of primitives '" + descriptor + "'");
    }
  }

  private int getNumberOfLeadingSquareBrackets() {
    int leadingSquareBrackets = 0;
    while (descriptor.content[leadingSquareBrackets] == '[') {
      leadingSquareBrackets++;
    }
    return leadingSquareBrackets;
  }

  public DexType toBaseType(DexItemFactory dexItemFactory) {
    int leadingSquareBrackets = getNumberOfLeadingSquareBrackets();
    if (leadingSquareBrackets == 0) {
      return this;
    }
    DexString newDesc = dexItemFactory.createString(descriptor.size - leadingSquareBrackets,
        Arrays.copyOfRange(descriptor.content, leadingSquareBrackets, descriptor.content.length));
    return dexItemFactory.createType(newDesc);
  }

  public DexType replaceBaseType(DexType newBase, DexItemFactory dexItemFactory) {
    assert this.isArrayType();
    assert !newBase.isArrayType();
    int leadingSquareBrackets = getNumberOfLeadingSquareBrackets();
    byte[] content = new byte[newBase.descriptor.content.length + leadingSquareBrackets];
    Arrays.fill(content, 0, leadingSquareBrackets, (byte) '[');
    System.arraycopy(newBase.descriptor.content, 0, content, leadingSquareBrackets,
        newBase.descriptor.content.length);
    DexString newDesc = dexItemFactory
        .createString(newBase.descriptor.size + leadingSquareBrackets, content);
    return dexItemFactory.createType(newDesc);
  }

  public DexType toArrayElementType(DexItemFactory dexItemFactory) {
    assert this.isArrayType();
    DexString newDesc = dexItemFactory.createString(descriptor.size - 1,
        Arrays.copyOfRange(descriptor.content, 1, descriptor.content.length));
    return dexItemFactory.createType(newDesc);
  }

  static boolean validateLevelsAreCorrect(Function<DexType, DexClass> definitions,
      DexItemFactory dexItemFactory) {
    Set<DexType> seenTypes = Sets.newIdentityHashSet();
    Deque<DexType> worklist = new ArrayDeque<>();
    DexType objectType = dexItemFactory.objectType;
    worklist.add(objectType);
    while (!worklist.isEmpty()) {
      DexType next = worklist.pop();
      DexClass nextHolder = definitions.apply(next);
      DexType superType;
      if (nextHolder == null) {
        // We might lack the definition of Object, so guard against that.
        superType = next == dexItemFactory.objectType ? null : dexItemFactory.objectType;
      } else {
        superType = nextHolder.superType;
      }
      assert !seenTypes.contains(next);
      seenTypes.add(next);
      if (superType == null) {
        assert next.hierarchyLevel == ROOT_LEVEL;
      } else {
        assert superType.hierarchyLevel == next.hierarchyLevel - 1
            || (superType.hierarchyLevel == ROOT_LEVEL && next.hierarchyLevel == INTERFACE_LEVEL);
        assert superType.directSubtypes.contains(next);
      }
      if (next.hierarchyLevel != INTERFACE_LEVEL) {
        // Only traverse the class hierarchy subtypes, not interfaces.
        worklist.addAll(next.directSubtypes);
      } else if (nextHolder != null) {
        // Test that the interfaces of this class are interfaces and have this class as subtype.
        for (DexType iface : nextHolder.interfaces.values) {
          assert iface.directSubtypes.contains(next);
          assert iface.hierarchyLevel == INTERFACE_LEVEL;
        }
      }
    }
    return true;
  }

  private String getPackageOrName(boolean packagePart) {
    assert isClassType();
    String descriptor = toDescriptorString();
    int lastSeparator = descriptor.lastIndexOf('/');
    if (lastSeparator == -1) {
      return packagePart ? "" : descriptor.substring(1, descriptor.length() - 1);
    } else {
      return packagePart ? descriptor.substring(1, lastSeparator)
          : descriptor.substring(lastSeparator + 1, descriptor.length() - 1);
    }
  }

  public DexType getSingleSubtype() {
    assert hierarchyLevel != UNKNOWN_LEVEL;
    if (directSubtypes.size() == 1) {
      return Iterables.getFirst(directSubtypes, null);
    } else {
      return null;
    }
  }

  public String getPackageDescriptor() {
    return getPackageOrName(true);
  }

  public String getName() {
    if (isPrimitiveType()) {
      return toSourceString();
    }
    return getPackageOrName(false);
  }

  public boolean isImmediateSubtypeOf(DexType type) {
    assert hierarchyLevel != UNKNOWN_LEVEL;
    return type.directSubtypes.contains(this);
  }
}
