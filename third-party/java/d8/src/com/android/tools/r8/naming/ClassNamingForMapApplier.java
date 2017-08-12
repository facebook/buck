// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming;

import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.naming.MemberNaming.FieldSignature;
import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import com.android.tools.r8.naming.MemberNaming.Signature;
import com.android.tools.r8.naming.MemberNaming.Signature.SignatureKind;
import com.android.tools.r8.utils.ThrowingConsumer;
import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Stores name information for a class.
 * <p>
 * The main differences of this against {@link ClassNamingForNameMapper} are:
 *   1) field and method mappings are maintained and searched separately for faster lookup;
 *   2) similar to the relation between {@link ClassNameMapper} and {@link SeedMapper}, this one
 *   uses original {@link Signature} as a key to look up {@link MemberNaming},
 *   whereas {@link ClassNamingForNameMapper} uses renamed {@link Signature} as a key; and thus
 *   3) logic of {@link #lookup} and {@link #lookupByOriginalSignature} are inverted; and
 *   4) {@link #lookupByOriginalItem}'s are introduced for lightweight lookup.
 */
public class ClassNamingForMapApplier implements ClassNaming {

  public static class Builder extends ClassNaming.Builder {
    private final String originalName;
    private final String renamedName;
    private final Map<MethodSignature, MemberNaming> methodMembers = new HashMap<>();
    private final Map<FieldSignature, MemberNaming> fieldMembers = new HashMap<>();

    private Builder(String renamedName, String originalName) {
      this.originalName = originalName;
      this.renamedName = renamedName;
    }

    @Override
    ClassNaming.Builder addMemberEntry(MemberNaming entry) {
      // Unlike {@link ClassNamingForNameMapper.Builder#addMemberEntry},
      // the key is original signature.
      if (entry.isMethodNaming()) {
        methodMembers.put((MethodSignature) entry.getOriginalSignature(), entry);
      } else {
        fieldMembers.put((FieldSignature) entry.getOriginalSignature(), entry);
      }
      return this;
    }

    @Override
    ClassNamingForMapApplier build() {
      return new ClassNamingForMapApplier(renamedName, originalName, methodMembers, fieldMembers);
    }
  }

  static Builder builder(String renamedName, String originalName) {
    return new Builder(renamedName, originalName);
  }

  private final String originalName;
  final String renamedName;

  private final ImmutableMap<MethodSignature, MemberNaming> methodMembers;
  private final ImmutableMap<FieldSignature, MemberNaming> fieldMembers;

  // Constructor to help chaining {@link ClassNamingForMapApplier} according to class hierarchy.
  ClassNamingForMapApplier(ClassNamingForMapApplier proxy) {
    this(proxy.renamedName, proxy.originalName, proxy.methodMembers, proxy.fieldMembers);
  }

  private ClassNamingForMapApplier(
      String renamedName,
      String originalName,
      Map<MethodSignature, MemberNaming> methodMembers,
      Map<FieldSignature, MemberNaming> fieldMembers) {
    this.renamedName = renamedName;
    this.originalName = originalName;
    this.methodMembers = ImmutableMap.copyOf(methodMembers);
    this.fieldMembers = ImmutableMap.copyOf(fieldMembers);
  }

  @Override
  public <T extends Throwable> void forAllMemberNaming(
      ThrowingConsumer<MemberNaming, T> consumer) throws T {
    forAllFieldNaming(consumer);
    forAllMethodNaming(consumer);
  }

  @Override
  public <T extends Throwable> void forAllFieldNaming(
      ThrowingConsumer<MemberNaming, T> consumer) throws T {
    for (MemberNaming naming : fieldMembers.values()) {
      consumer.accept(naming);
    }
  }

  @Override
  public <T extends Throwable> void forAllMethodNaming(
      ThrowingConsumer<MemberNaming, T> consumer) throws T {
    for (MemberNaming naming : methodMembers.values()) {
      consumer.accept(naming);
    }
  }

  @Override
  public MemberNaming lookup(Signature renamedSignature) {
    // As the key is inverted, this looks a lot like
    //   {@link ClassNamingForNameMapper#lookupByOriginalSignature}.
    if (renamedSignature.kind() == SignatureKind.METHOD) {
      for (MemberNaming memberNaming : methodMembers.values()) {
        if (memberNaming.getRenamedSignature().equals(renamedSignature)) {
          return memberNaming;
        }
      }
      return null;
    } else {
      assert renamedSignature.kind() == SignatureKind.FIELD;
      for (MemberNaming memberNaming : fieldMembers.values()) {
        if (memberNaming.getRenamedSignature().equals(renamedSignature)) {
          return memberNaming;
        }
      }
      return null;
    }
  }

  @Override
  public MemberNaming lookupByOriginalSignature(Signature original) {
    // As the key is inverted, this looks a lot like {@link ClassNamingForNameMapper#lookup}.
    if (original.kind() == SignatureKind.METHOD) {
      return methodMembers.get(original);
    } else {
      assert original.kind() == SignatureKind.FIELD;
      return fieldMembers.get(original);
    }
  }

  MemberNaming lookupByOriginalItem(DexField field) {
    for (Map.Entry<FieldSignature, MemberNaming> entry : fieldMembers.entrySet()) {
      FieldSignature signature = entry.getKey();
      if (signature.name.equals(field.name.toString())
          && signature.type.equals(field.type.getName())) {
        return entry.getValue();
      }
    }
    return null;
  }

  protected MemberNaming lookupByOriginalItem(DexMethod method) {
    for (Map.Entry<MethodSignature, MemberNaming> entry : methodMembers.entrySet()) {
      MethodSignature signature = entry.getKey();
      if (signature.name.equals(method.name.toString())
          && signature.type.equals(method.proto.returnType.toString())
          && Arrays.equals(signature.parameters,
              Arrays.stream(method.proto.parameters.values)
                  .map(DexType::toString).toArray(String[]::new))) {
        return entry.getValue();
      }
    }
    return null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ClassNamingForMapApplier)) {
      return false;
    }

    ClassNamingForMapApplier that = (ClassNamingForMapApplier) o;

    return originalName.equals(that.originalName)
        && renamedName.equals(that.renamedName)
        && methodMembers.equals(that.methodMembers)
        && fieldMembers.equals(that.fieldMembers);
  }

  @Override
  public int hashCode() {
    int result = originalName.hashCode();
    result = 31 * result + renamedName.hashCode();
    result = 31 * result + methodMembers.hashCode();
    result = 31 * result + fieldMembers.hashCode();
    return result;
  }
}
