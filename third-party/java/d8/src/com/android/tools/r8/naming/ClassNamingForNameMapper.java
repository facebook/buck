// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming;

import com.android.tools.r8.naming.MemberNaming.FieldSignature;
import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import com.android.tools.r8.naming.MemberNaming.Signature;
import com.android.tools.r8.naming.MemberNaming.Signature.SignatureKind;
import com.android.tools.r8.utils.ThrowingConsumer;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stores name information for a class.
 * <p>
 * This includes how the class was renamed and information on the classes members.
 */
public class ClassNamingForNameMapper implements ClassNaming {

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
      if (entry.isMethodNaming()) {
        methodMembers.put((MethodSignature) entry.getRenamedSignature(), entry);
      } else {
        fieldMembers.put((FieldSignature) entry.getRenamedSignature(), entry);
      }
      return this;
    }

    @Override
    ClassNamingForNameMapper build() {
      return new ClassNamingForNameMapper(renamedName, originalName, methodMembers, fieldMembers);
    }
  }

  static Builder builder(String renamedName, String originalName) {
    return new Builder(renamedName, originalName);
  }

  public final String originalName;
  private final String renamedName;

  /**
   * Mapping from the renamed signature to the naming information for a member.
   * <p>
   * A renamed signature is a signature where the member's name has been obfuscated but not the type
   * information.
   **/
  private final ImmutableMap<MethodSignature, MemberNaming> methodMembers;
  private final ImmutableMap<FieldSignature, MemberNaming> fieldMembers;

  private ClassNamingForNameMapper(
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
  public MemberNaming lookup(Signature renamedSignature) {
    if (renamedSignature.kind() == SignatureKind.METHOD) {
      return methodMembers.get(renamedSignature);
    } else {
      assert renamedSignature.kind() == SignatureKind.FIELD;
      return fieldMembers.get(renamedSignature);
    }
  }

  @Override
  public MemberNaming lookupByOriginalSignature(Signature original) {
    if (original.kind() == SignatureKind.METHOD) {
      for (MemberNaming memberNaming: methodMembers.values()) {
        if (memberNaming.signature.equals(original)) {
          return memberNaming;
        }
      }
      return null;
    } else {
      assert original.kind() == SignatureKind.FIELD;
      for (MemberNaming memberNaming : fieldMembers.values()) {
        if (memberNaming.signature.equals(original)) {
          return memberNaming;
        }
      }
      return null;
    }
  }

  public List<MemberNaming> lookupByOriginalName(String originalName) {
    List<MemberNaming> result = new ArrayList<>();
    for (MemberNaming naming : methodMembers.values()) {
      if (naming.signature.name.equals(originalName)) {
        result.add(naming);
      }
    }
    for (MemberNaming naming : fieldMembers.values()) {
      if (naming.signature.name.equals(originalName)) {
        result.add(naming);
      }
    }
    return result;
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

  void write(Writer writer, boolean collapseRanges) throws IOException {
    writer.append(originalName);
    writer.append(" -> ");
    writer.append(renamedName);
    writer.append(":\n");
    forAllMemberNaming(memberNaming -> memberNaming.write(writer, collapseRanges, true));
  }

  @Override
  public String toString() {
    try {
      StringWriter writer = new StringWriter();
      write(writer, false);
      return writer.toString();
    } catch (IOException e) {
      return e.toString();
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ClassNamingForNameMapper)) {
      return false;
    }

    ClassNamingForNameMapper that = (ClassNamingForNameMapper) o;

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

