/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.rules.modern;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.modern.impl.DefaultClassInfoFactory;
import com.facebook.buck.rules.modern.impl.ValueTypeInfo;
import com.facebook.buck.rules.modern.impl.ValueTypeInfoFactory;
import com.facebook.buck.rules.modern.impl.ValueVisitor;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.reflect.TypeToken;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Implementation of Serialization of Buildables.
 *
 * <p>This works by walking all referenced values with a ValueVisitor.
 *
 * <p>Referenced "dynamic" objects (i.e. implementations of AddsToRuleKey) are serialized as simply
 * a hash of their serialized representation. The serialization of a complex object is then
 * effectively a merkle tree. This allows us to share the serialized representation of shared
 * objects (for c++ particularly, there are many shared references to the PreprocessorDelegate and
 * other such fields).
 */
public class Serializer {
  private static final int MAX_INLINE_LENGTH = 100;
  private final ConcurrentHashMap<AddsToRuleKey, Either<HashCode, byte[]>> cache =
      new ConcurrentHashMap<>();
  private final SourcePathRuleFinder ruleFinder;
  private final ImmutableMap<Path, Optional<String>> cellMap;
  private final Delegate delegate;
  private final Path rootCellPath;

  /**
   * The first time a "dynamic" object is encountered (including Buildables themselves),
   * registerNewValue will be called. This should return a unique HashCode representation (this
   * hashCode will become the serialized representation of that object and deserialization will
   * depend on a Deserializer.DataProvider to do the reverse lookup back to data/children).
   */
  public interface Delegate {
    HashCode registerNewValue(
        AddsToRuleKey instance, byte[] data, ImmutableList<HashCode> children);
  }

  public Serializer(
      SourcePathRuleFinder ruleFinder, CellPathResolver cellResolver, Delegate delegate) {
    this.ruleFinder = ruleFinder;
    this.delegate = delegate;
    ImmutableMap.Builder<Path, Optional<String>> builder = ImmutableMap.builder();
    cellResolver.getCellPaths().forEach((name, path) -> builder.put(path, Optional.of(name)));
    builder.put(cellResolver.getCellPathOrThrow(Optional.empty()), Optional.empty());
    this.cellMap = builder.build();
    this.rootCellPath = cellResolver.getCellPathOrThrow(Optional.empty());
  }

  /**
   * Serializes an object. For small objects, the full serialized format will be returned as a
   * byte[]. For larger objects, the representation will be recorded with the delegate and the hash
   * will be returned.
   */
  public <T extends AddsToRuleKey> HashCode serialize(T instance) throws IOException {
    Either<HashCode, byte[]> serialize =
        serialize(instance, DefaultClassInfoFactory.forInstance(instance));
    return serialize.transform(
        left -> left, right -> delegate.registerNewValue(instance, right, ImmutableList.of()));
  }

  /** See Serialize(T instance) above. */
  public <T extends AddsToRuleKey> Either<HashCode, byte[]> serialize(
      T instance, ClassInfo<T> classInfo) throws IOException {
    if (cache.contains(instance)) {
      return Preconditions.checkNotNull(cache.get(instance));
    }
    Visitor visitor = new Visitor(instance.getClass());
    classInfo.visit(instance, visitor);

    return Preconditions.checkNotNull(
        cache.computeIfAbsent(
            instance,
            ignored -> {
              byte[] data = visitor.byteStream.toByteArray();
              ImmutableList<HashCode> children =
                  visitor.children.build().distinct().collect(ImmutableList.toImmutableList());
              return data.length < MAX_INLINE_LENGTH && children.isEmpty()
                  ? Either.ofRight(data)
                  : Either.ofLeft(registerNewValue(instance, data, children));
            }));
  }

  private <T extends AddsToRuleKey> HashCode registerNewValue(
      T instance, byte[] data, ImmutableList<HashCode> children) {
    return delegate.registerNewValue(instance, data, children);
  }

  private class Visitor implements ValueVisitor<IOException> {
    ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
    DataOutputStream stream = new DataOutputStream(byteStream);
    Stream.Builder<HashCode> children = Stream.builder();

    public Visitor(Class<? extends AddsToRuleKey> clazz) throws IOException {
      writeString(clazz.getName());
    }

    @Override
    public <T> void visitList(ImmutableList<T> value, ValueTypeInfo<T> innerType)
        throws IOException {
      stream.writeInt(value.size());
      for (T e : value) {
        innerType.visit(e, this);
      }
    }

    @Override
    public <T> void visitSet(ImmutableSortedSet<T> value, ValueTypeInfo<T> innerType)
        throws IOException {
      stream.writeInt(value.size());
      for (T e : value) {
        innerType.visit(e, this);
      }
    }

    @Override
    public <T> void visitOptional(Optional<T> value, ValueTypeInfo<T> innerType)
        throws IOException {
      stream.writeBoolean(value.isPresent());
      if (value.isPresent()) {
        innerType.visit(value.get(), this);
      }
    }

    @Override
    public void visitOutputPath(OutputPath value) throws IOException {
      writeString(value.getPath().toString());
    }

    private void writeString(String value) throws IOException {
      // TODO(cjhopman): This doesn't correctly handle large strings.
      Preconditions.checkState(value.length() < 10000);
      stream.writeUTF(value);
    }

    @Override
    public void visitSourcePath(SourcePath value) throws IOException {
      if (value instanceof DefaultBuildTargetSourcePath) {
        value = ruleFinder.getRule(value).get().getSourcePathToOutput();
        Preconditions.checkNotNull(value);
      }

      if (value instanceof ExplicitBuildTargetSourcePath) {
        stream.writeBoolean(true);
        ExplicitBuildTargetSourcePath buildTargetSourcePath = (ExplicitBuildTargetSourcePath) value;
        writeValue(buildTargetSourcePath.getTarget(), new TypeToken<BuildTarget>() {});
        writeString(buildTargetSourcePath.getResolvedPath().toString());
      } else if (value instanceof PathSourcePath) {
        PathSourcePath pathSourcePath = (PathSourcePath) value;
        stream.writeBoolean(false);
        writeValue(
            getCellName(pathSourcePath.getFilesystem()), new TypeToken<Optional<String>>() {});
        writeString(pathSourcePath.getRelativePathName());
      } else {
        throw new IllegalStateException(
            String.format("Cannot serialize SourcePath of type %s.", value.getClass().getName()));
      }
    }

    private <T> void writeValue(T value, TypeToken<T> type) throws IOException {
      ValueTypeInfoFactory.forTypeToken(type).visit(value, this);
    }

    @Override
    public <T> void visitField(Field field, T value, ValueTypeInfo<T> valueTypeInfo)
        throws IOException {
      valueTypeInfo.visit(value, this);
    }

    @Override
    public <T extends AddsToRuleKey> void visitDynamic(T value, ClassInfo<T> classInfo)
        throws IOException {
      Either<HashCode, byte[]> serialized = serialize(value, classInfo);
      if (serialized.isLeft()) {
        stream.writeBoolean(true);
        writeBytes(serialized.getLeft().asBytes());
        children.add(serialized.getLeft());
      } else {
        stream.writeBoolean(false);
        writeBytes(serialized.getRight());
      }
    }

    @Override
    public void visitPath(Path path) throws IOException {
      if (path.isAbsolute()) {
        stream.writeBoolean(true);
        Path cellPath = rootCellPath;
        Optional<String> cellName = Optional.empty();
        for (Map.Entry<Path, Optional<String>> candidate : cellMap.entrySet()) {
          if (path.startsWith(candidate.getKey())) {
            cellPath = candidate.getKey();
            cellName = candidate.getValue();
          }
        }
        ValueTypeInfoFactory.forTypeToken(new TypeToken<Optional<String>>() {})
            .visit(cellName, this);
        writeString(cellPath.relativize(path).toString());
      } else {
        stream.writeBoolean(false);
        stream.writeUTF(path.toString());
      }
    }

    @Override
    public void visitString(String value) throws IOException {
      writeString(value);
    }

    @Override
    public void visitCharacter(Character value) throws IOException {
      stream.writeChar(value);
    }

    @Override
    public void visitBoolean(Boolean value) throws IOException {
      stream.writeBoolean(value);
    }

    @Override
    public void visitByte(Byte value) throws IOException {
      stream.writeByte(value);
    }

    @Override
    public void visitShort(Short value) throws IOException {
      stream.writeShort(value);
    }

    @Override
    public void visitInteger(Integer value) throws IOException {
      stream.writeInt(value);
    }

    @Override
    public void visitLong(Long value) throws IOException {
      stream.writeLong(value);
    }

    @Override
    public void visitFloat(Float value) throws IOException {
      stream.writeFloat(value);
    }

    @Override
    public void visitDouble(Double value) throws IOException {
      stream.writeDouble(value);
    }

    private void writeBytes(byte[] bytes) throws IOException {
      this.stream.writeInt(bytes.length);
      this.stream.write(bytes);
    }
  }

  private Optional<String> getCellName(ProjectFilesystem filesystem) {
    return Preconditions.checkNotNull(cellMap.get(filesystem.getRootPath()));
  }
}
