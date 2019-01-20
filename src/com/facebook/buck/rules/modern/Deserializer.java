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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.modern.annotations.CustomClassBehaviorTag;
import com.facebook.buck.core.rules.modern.annotations.CustomFieldBehavior;
import com.facebook.buck.core.rules.modern.annotations.DefaultFieldSerialization;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.impl.DefaultClassInfoFactory;
import com.facebook.buck.rules.modern.impl.ValueTypeInfoFactory;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.objenesis.ObjenesisStd;
import org.objenesis.instantiator.ObjectInstantiator;

/**
 * Implements deserialization of Buildables.
 *
 * <p>This works by walking all referenced fields and creating them with a ValueCreator. It uses
 * Objenesis to create objects and then injects the field values via reflection.
 */
public class Deserializer {
  private ObjenesisStd objenesis = new ObjenesisStd();
  private Map<Class<?>, ObjectInstantiator> instantiators = new ConcurrentHashMap<>();
  private Map<HashCode, AddsToRuleKey> childCache = new ConcurrentHashMap<>();

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
  private final Supplier<SourcePathResolver> pathResolver;
  private final ToolchainProvider toolchainProvider;

  public Deserializer(
      Function<Optional<String>, ProjectFilesystem> cellMap,
      ClassFinder classFinder,
      Supplier<SourcePathResolver> pathResolver,
      ToolchainProvider toolchainProvider) {
    this.cellMap = cellMap;
    this.classFinder = classFinder;
    this.pathResolver = pathResolver;
    this.toolchainProvider = toolchainProvider;
  }

  public <T extends AddsToRuleKey> T deserialize(DataProvider provider, Class<T> clazz)
      throws IOException {
    try (DataInputStream stream = new DataInputStream(provider.getData())) {
      return new Creator(provider, stream).create(clazz);
    }
  }

  private class Creator implements ValueCreator<IOException> {
    private final DataInputStream stream;
    private final DataProvider provider;

    private Creator(DataProvider provider, DataInputStream stream) {
      this.stream = stream;
      this.provider = provider;
    }

    @Override
    public <T> T createSpecial(Class<T> valueClass, Object... args) {
      if (valueClass.equals(SourcePathResolver.class)) {
        Preconditions.checkState(args.length == 0);
        @SuppressWarnings("unchecked")
        T value = (T) pathResolver.get();
        return value;
      } else if (valueClass.equals(ToolchainProvider.class)) {
        @SuppressWarnings("unchecked")
        T value = (T) toolchainProvider;
        return value;
      }
      throw new IllegalArgumentException();
    }

    @Override
    public AddsToRuleKey createDynamic() throws IOException {
      DataProvider childProvider;
      if (stream.readBoolean()) {
        HashCode hash = HashCode.fromBytes(readBytes());
        if (childCache.containsKey(hash)) {
          return childCache.get(hash);
        }
        childProvider = provider.getChild(hash);
        AddsToRuleKey child = deserialize(childProvider, AddsToRuleKey.class);
        return childCache.computeIfAbsent(hash, ignored -> child);
      } else {
        byte[] data = readBytes();
        return deserialize(
            new DataProvider() {
              @Override
              public InputStream getData() {
                return new ByteArrayInputStream(data);
              }

              @Override
              public DataProvider getChild(HashCode hash) {
                throw new IllegalStateException();
              }
            },
            AddsToRuleKey.class);
      }
    }

    @Override
    public <T> ImmutableList<T> createList(ValueTypeInfo<T> innerType) throws IOException {
      int size = stream.readInt();
      ImmutableList.Builder<T> builder = ImmutableList.builderWithExpectedSize(size);
      for (int i = 0; i < size; i++) {
        builder.add(innerType.createNotNull(this));
      }
      return builder.build();
    }

    @Override
    public <T> ImmutableSet<T> createSet(ValueTypeInfo<T> innerType) throws IOException {
      int size = stream.readInt();
      ImmutableSet.Builder<T> builder = ImmutableSet.builderWithExpectedSize(size);
      for (int i = 0; i < size; i++) {
        builder.add(innerType.createNotNull(this));
      }
      return builder.build();
    }

    @Override
    public <T> ImmutableSortedSet<T> createSortedSet(ValueTypeInfo<T> innerType)
        throws IOException {
      int size = stream.readInt();
      @SuppressWarnings("unchecked")
      ImmutableSortedSet.Builder<T> builder =
          (ImmutableSortedSet.Builder<T>) ImmutableSortedSet.naturalOrder();
      for (int i = 0; i < size; i++) {
        builder.add(innerType.createNotNull(this));
      }
      return builder.build();
    }

    @Override
    public <T> Optional<T> createOptional(ValueTypeInfo<T> innerType) throws IOException {
      if (stream.readBoolean()) {
        return Optional.of(innerType.createNotNull(this));
      }
      return Optional.empty();
    }

    @Override
    @Nullable
    public <T> T createNullable(ValueTypeInfo<T> innerType) throws IOException {
      if (stream.readBoolean()) {
        return innerType.create(this);
      }
      return null;
    }

    @Override
    public OutputPath createOutputPath() throws IOException {
      boolean isPublic = stream.readBoolean();
      String path = stream.readUTF();
      return isPublic ? new PublicOutputPath(Paths.get(path)) : new OutputPath(path);
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
      return ValueTypeInfoFactory.forTypeToken(typeToken).createNotNull(this);
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

      Optional<CustomClassBehaviorTag> serializerTag =
          CustomBehaviorUtils.getBehavior(instanceClass, CustomClassSerialization.class);
      if (serializerTag.isPresent()) {
        @SuppressWarnings("unchecked")
        CustomClassSerialization<T> customSerializer =
            (CustomClassSerialization<T>) serializerTag.get();
        return customSerializer.deserialize(this);
      }

      ObjectInstantiator instantiator =
          instantiators.computeIfAbsent(instanceClass, objenesis::getInstantiatorOf);
      @SuppressWarnings("unchecked")
      T instance = (T) instantiator.newInstance();
      ClassInfo<? super T> classInfo = DefaultClassInfoFactory.forInstance(instance);

      initialize(instance, classInfo);

      return instance;
    }

    private <T extends AddsToRuleKey> void initialize(T instance, ClassInfo<? super T> classInfo)
        throws IOException {
      if (classInfo.getSuperInfo().isPresent()) {
        initialize(instance, classInfo.getSuperInfo().get());
      }

      ImmutableCollection<FieldInfo<?>> fields = classInfo.getFieldInfos();
      for (FieldInfo<?> info : fields) {
        try {
          Object value = createForField(info);
          setField(info.getField(), instance, value);
        } catch (Exception e) {
          Throwables.throwIfInstanceOf(e, IOException.class);
          throw new BuckUncheckedExecutionException(
              e,
              "When trying to initialize %s.%s.",
              instance.getClass().getName(),
              info.getField().getName());
        }
      }
    }

    @Nullable
    private <T> T createForField(FieldInfo<T> info) throws IOException {
      Optional<CustomFieldBehavior> behavior = info.getCustomBehavior();
      if (behavior.isPresent()) {
        if (CustomBehaviorUtils.get(behavior.get(), DefaultFieldSerialization.class).isPresent()) {
          @SuppressWarnings("unchecked")
          ValueTypeInfo<T> typeInfo =
              (ValueTypeInfo<T>)
                  ValueTypeInfoFactory.forTypeToken(TypeToken.of(info.getField().getGenericType()));
          return typeInfo.create(this);
        }

        Optional<?> serializerTag =
            CustomBehaviorUtils.get(behavior, CustomFieldSerialization.class);
        if (serializerTag.isPresent()) {
          @SuppressWarnings("unchecked")
          CustomFieldSerialization<T> customSerializer =
              (CustomFieldSerialization<T>) serializerTag.get();
          return customSerializer.deserialize(this);
        }
      }

      return info.getValueTypeInfo().create(this);
    }

    private void setField(Field field, Object instance, @Nullable Object value)
        throws IllegalAccessException {
      field.setAccessible(true);
      field.set(instance, value);
    }

    @Override
    public <K, V> ImmutableMap<K, V> createMap(ValueTypeInfo<K> keyType, ValueTypeInfo<V> valueType)
        throws IOException {
      int size = stream.readInt();
      ImmutableMap.Builder<K, V> builder = ImmutableMap.builderWithExpectedSize(size);
      for (int i = 0; i < size; i++) {
        builder.put(keyType.createNotNull(this), valueType.createNotNull(this));
      }
      return builder.build();
    }

    @Override
    public <K, V> ImmutableSortedMap<K, V> createSortedMap(
        ValueTypeInfo<K> keyType, ValueTypeInfo<V> valueType) throws IOException {
      int size = stream.readInt();
      @SuppressWarnings("unchecked")
      ImmutableSortedMap.Builder<K, V> builder =
          (ImmutableSortedMap.Builder<K, V>) ImmutableSortedMap.naturalOrder();
      for (int i = 0; i < size; i++) {
        builder.put(keyType.createNotNull(this), valueType.createNotNull(this));
      }
      return builder.build();
    }
  }
}
