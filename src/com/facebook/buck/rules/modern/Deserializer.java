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
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.modern.impl.DefaultClassInfoFactory;
import com.facebook.buck.rules.modern.impl.FieldInfo;
import com.facebook.buck.rules.modern.impl.ValueCreator;
import com.facebook.buck.rules.modern.impl.ValueTypeInfo;
import com.facebook.buck.rules.modern.impl.ValueTypeInfoFactory;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.io.ByteStreams;
import com.google.common.reflect.TypeToken;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Function;
import org.objenesis.ObjenesisStd;

/**
 * Implements deserialization of Buildables.
 *
 * <p>This works by walking all referenced fields and creating them with a ValueCreator. It uses
 * Objenesis to create objects and then injects the field values via reflection.
 */
public class Deserializer {

  /**
   * DataProviders are used for deserializing "dynamic" objects. These are serialized as hashcodes
   * and the DataProvider is expected to map those back to the corresponding serialized
   * representation.
   */
  public interface DataProvider {
    InputStream getData();

    DataProvider getChild(HashCode hash);
  }

  /**
   * Used for looking up classes. It's not necessarily the case that every serialized class is
   * loadable from Deserializer's ClassLoader.
   */
  public interface ClassFinder {
    Class<?> find(String name) throws ClassNotFoundException;
  }

  private final Function<Optional<String>, ProjectFilesystem> cellMap;
  private final ClassFinder classFinder;

  public Deserializer(
      Function<Optional<String>, ProjectFilesystem> cellMap, ClassFinder classFinder) {
    this.cellMap = cellMap;
    this.classFinder = classFinder;
  }

  public <T extends AddsToRuleKey> T deserialize(DataProvider provider, Class<T> clazz)
      throws IOException {
    return new Creator(provider).create(clazz);
  }

  private class Creator implements ValueCreator<IOException> {
    private final DataInputStream stream;
    private final DataProvider provider;

    private Creator(DataProvider provider) {
      this.stream = new DataInputStream(provider.getData());
      this.provider = provider;
    }

    @Override
    public AddsToRuleKey createDynamic() throws IOException {
      DataProvider childProvider;
      if (stream.readBoolean()) {
        childProvider = provider.getChild(HashCode.fromBytes(readBytes()));
      } else {
        byte[] data = readBytes();
        childProvider =
            new DataProvider() {
              @Override
              public InputStream getData() {
                return new ByteArrayInputStream(data);
              }

              @Override
              public DataProvider getChild(HashCode hash) {
                throw new IllegalStateException();
              }
            };
      }
      return deserialize(childProvider, AddsToRuleKey.class);
    }

    @Override
    public <T> ImmutableList<T> createList(ValueTypeInfo<T> innerType) throws IOException {
      int size = stream.readInt();
      ImmutableList.Builder<T> builder = ImmutableList.builderWithExpectedSize(size);
      for (int i = 0; i < size; i++) {
        builder.add(innerType.create(this));
      }
      return builder.build();
    }

    @Override
    public <T> ImmutableSortedSet<T> createSet(ValueTypeInfo<T> innerType) throws IOException {
      int size = stream.readInt();
      @SuppressWarnings("unchecked")
      ImmutableSortedSet.Builder<T> builder =
          (ImmutableSortedSet.Builder<T>) ImmutableSortedSet.naturalOrder();
      for (int i = 0; i < size; i++) {
        builder.add(innerType.create(this));
      }
      return builder.build();
    }

    @Override
    public <T> Optional<T> createOptional(ValueTypeInfo<T> innerType) throws IOException {
      if (stream.readBoolean()) {
        return Optional.of(innerType.create(this));
      }
      return Optional.empty();
    }

    @Override
    public OutputPath createOutputPath() throws IOException {
      return new OutputPath(stream.readUTF());
    }

    @Override
    public SourcePath createSourcePath() throws IOException {
      if (stream.readBoolean()) {
        BuildTarget target = readValue(new TypeToken<BuildTarget>() {});
        Path path = Paths.get(stream.readUTF());
        return ExplicitBuildTargetSourcePath.of(target, path);
      } else {
        Optional<String> cellName = readValue(new TypeToken<Optional<String>>() {});
        Path path = Paths.get(stream.readUTF());
        return PathSourcePath.of(cellMap.apply(cellName), path);
      }
    }

    @Override
    public Path createPath() throws IOException {
      if (stream.readBoolean()) {
        Optional<String> cellName = readValue(new TypeToken<Optional<String>>() {});
        Path relativePath = Paths.get(stream.readUTF());
        return cellMap.apply(cellName).resolve(relativePath);
      } else {
        return Paths.get(stream.readUTF());
      }
    }

    @Override
    public String createString() throws IOException {
      return stream.readUTF();
    }

    @Override
    public Character createCharacter() throws IOException {
      return stream.readChar();
    }

    @Override
    public Boolean createBoolean() throws IOException {
      return stream.readBoolean();
    }

    @Override
    public Byte createByte() throws IOException {
      return stream.readByte();
    }

    @Override
    public Short createShort() throws IOException {
      return stream.readShort();
    }

    @Override
    public Integer createInteger() throws IOException {
      return stream.readInt();
    }

    @Override
    public Long createLong() throws IOException {
      return stream.readLong();
    }

    @Override
    public Float createFloat() throws IOException {
      return stream.readFloat();
    }

    @Override
    public Double createDouble() throws IOException {
      return stream.readDouble();
    }

    private byte[] readBytes() throws IOException {
      int size = stream.readInt();
      byte[] data = new byte[size];
      ByteStreams.readFully(stream, data);
      return data;
    }

    private <T> T readValue(TypeToken<T> typeToken) throws IOException {
      return ValueTypeInfoFactory.forTypeToken(typeToken).create(this);
    }

    public <T extends AddsToRuleKey> T create(Class<T> requestedClass) throws IOException {
      String className = stream.readUTF();
      Class<?> instanceClass;
      try {
        instanceClass = classFinder.find(className);
      } catch (ClassNotFoundException e) {
        throw new RuntimeException(e);
      }
      Preconditions.checkState(requestedClass.isAssignableFrom(instanceClass));
      @SuppressWarnings("unchecked")
      T instance = (T) new ObjenesisStd().newInstance(instanceClass);
      ClassInfo<? super T> classInfo = DefaultClassInfoFactory.forInstance(instance);

      if (classInfo.getSuperInfo().isPresent()) {
        initialize(instance, classInfo.getSuperInfo().get());
      }
      initialize(instance, classInfo);

      return instance;
    }

    private <T extends AddsToRuleKey> void initialize(T instance, ClassInfo<? super T> classInfo)
        throws IOException {
      ImmutableCollection<FieldInfo<?>> fields = classInfo.getFieldInfos();
      for (FieldInfo<?> info : fields) {
        try {
          Object value = info.getValueTypeInfo().create(this);
          setField(info.getField(), instance, value);
        } catch (Exception e) {
          Throwables.throwIfInstanceOf(e, IOException.class);
          throw new RuntimeException(e);
        }
      }
    }

    private void setField(Field field, Object instance, Object value)
        throws IllegalAccessException {
      field.setAccessible(true);
      field.set(instance, value);
    }
  }
}
