// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming;

import static com.android.tools.r8.utils.DescriptorUtils.descriptorToJavaType;

import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.IndexedDexItem;
import com.android.tools.r8.naming.MemberNaming.FieldSignature;
import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import com.android.tools.r8.naming.MemberNaming.Signature;
import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class ClassNameMapper implements ProguardMap {

  static class Builder extends ProguardMap.Builder {
    final ImmutableMap.Builder<String, ClassNamingForNameMapper.Builder> mapBuilder;

    private Builder() {
      mapBuilder = ImmutableMap.builder();
    }

    @Override
    ClassNamingForNameMapper.Builder classNamingBuilder(String renamedName, String originalName) {
      ClassNamingForNameMapper.Builder classNamingBuilder =
          ClassNamingForNameMapper.builder(renamedName, originalName);
      mapBuilder.put(renamedName, classNamingBuilder);
      return classNamingBuilder;
    }

    @Override
    ClassNameMapper build(){
      return new ClassNameMapper(mapBuilder.build());
    }
  }

  static Builder builder() {
    return new Builder();
  }

  public static ClassNameMapper mapperFromInputStream(InputStream in) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    try (ProguardMapReader proguardReader = new ProguardMapReader(reader)) {
      ClassNameMapper.Builder builder = ClassNameMapper.builder();
      proguardReader.parse(builder);
      return builder.build();
    }
  }

  public static ClassNameMapper mapperFromFile(Path path) throws IOException {
    return mapperFromInputStream(Files.newInputStream(path));
  }

  static ClassNameMapper mapperFromString(String contents) throws IOException {
    return mapperFromInputStream(
        new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8)));
  }

  private final ImmutableMap<String, ClassNamingForNameMapper> classNameMappings;
  private ImmutableBiMap<String, String> nameMapping;

  private Map<Signature, Signature> signatureMap = new HashMap<>();

  private ClassNameMapper(Map<String, ClassNamingForNameMapper.Builder> classNameMappings) {
    ImmutableMap.Builder<String, ClassNamingForNameMapper> builder = ImmutableMap.builder();
    for(Map.Entry<String, ClassNamingForNameMapper.Builder> entry : classNameMappings.entrySet()) {
      builder.put(entry.getKey(), entry.getValue().build());
    }
    this.classNameMappings = builder.build();
  }

  private Signature canonicalizeSignature(Signature signature) {
    Signature result = signatureMap.get(signature);
    if (result != null) {
      return result;
    }
    signatureMap.put(signature, signature);
    return signature;
  }

  public MethodSignature getRenamedMethodSignature(DexMethod method) {
    DexType[] parameters = method.proto.parameters.values;
    String[] parameterTypes = new String[parameters.length];
    for (int i = 0; i < parameters.length; i++) {
      parameterTypes[i] = deobfuscateType(parameters[i].toDescriptorString());
    }
    String returnType = deobfuscateType(method.proto.returnType.toDescriptorString());

    MethodSignature signature = new MethodSignature(method.name.toString(), returnType,
        parameterTypes);
    return (MethodSignature) canonicalizeSignature(signature);
  }

  public Signature getRenamedFieldSignature(DexField field) {
    String type = deobfuscateType(field.type.toDescriptorString());
    return canonicalizeSignature(new FieldSignature(field.name.toString(), type));
  }

  /**
   * Deobfuscate a class name.
   *
   * Returns the deobfuscated name if a mapping was found. Otherwise it returns the passed in name.
   */
  public String deobfuscateClassName(String name) {
    ClassNamingForNameMapper classNaming = classNameMappings.get(name);
    if (classNaming == null) {
      return name;
    }
    return classNaming.originalName;
  }

  private String deobfuscateType(String asString) {
    return descriptorToJavaType(asString, this);
  }

  @Override
  public boolean hasMapping(DexType type) {
    String decoded = descriptorToJavaType(type.descriptor.toString());
    return classNameMappings.containsKey(decoded);
  }

  @Override
  public ClassNamingForNameMapper getClassNaming(DexType type) {
    String decoded = descriptorToJavaType(type.descriptor.toString());
    return classNameMappings.get(decoded);
  }

  public ClassNamingForNameMapper getClassNaming(String name) {
    return classNameMappings.get(name);
  }

  public void write(Writer writer, boolean collapseRanges) throws IOException {
    for (ClassNamingForNameMapper naming : classNameMappings.values()) {
      naming.write(writer, collapseRanges);
    }
  }

  public void forAllClassNamings(Consumer<ClassNaming> consumer) {
    classNameMappings.values().forEach(consumer);
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

  public BiMap<String, String> getObfuscatedToOriginalMapping() {
    if (nameMapping == null) {
      ImmutableBiMap.Builder<String, String> builder = ImmutableBiMap.builder();
      for (String name : classNameMappings.keySet()) {
        builder.put(name, classNameMappings.get(name).originalName);
      }
      nameMapping = builder.build();
    }
    return nameMapping;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ClassNameMapper
        && classNameMappings.equals(((ClassNameMapper) o).classNameMappings);
  }

  @Override
  public int hashCode() {
    return 31 * classNameMappings.hashCode();
  }

  public String originalNameOf(IndexedDexItem item) {
    if (item instanceof DexField) {
      return lookupName(getRenamedFieldSignature((DexField) item), ((DexField) item).clazz);
    } else if (item instanceof DexMethod) {
      return lookupName(getRenamedMethodSignature((DexMethod) item), ((DexMethod) item).holder);
    } else if (item instanceof DexType) {
      return descriptorToJavaType(((DexType) item).toDescriptorString(), this);
    } else {
      return item.toString();
    }
  }

  private String lookupName(Signature signature, DexType clazz) {
    String decoded = descriptorToJavaType(clazz.descriptor.toString());
    ClassNamingForNameMapper classNaming = getClassNaming(decoded);
    if (classNaming == null) {
      return decoded + " " + signature.toString();
    }
    MemberNaming memberNaming = classNaming.lookup(signature);
    if (memberNaming == null) {
      return classNaming.originalName + " " + signature.toString();
    }
    return classNaming.originalName + " " + memberNaming.signature.toString();
  }

  public Signature originalSignatureOf(DexMethod method) {
    String decoded = descriptorToJavaType(method.holder.descriptor.toString());
    MethodSignature memberSignature = getRenamedMethodSignature(method);
    ClassNaming classNaming = getClassNaming(decoded);
    if (classNaming == null) {
      return memberSignature;
    }
    MemberNaming memberNaming = classNaming.lookup(memberSignature);
    if (memberNaming == null) {
      return memberSignature;
    }
    return memberNaming.signature;
  }

  public String originalNameOf(DexType clazz) {
    return deobfuscateType(clazz.descriptor.toString());
  }
}
