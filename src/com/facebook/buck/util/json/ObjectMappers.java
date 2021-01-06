/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.util.json;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNodeWithDeps;
import com.facebook.buck.core.parser.buildtargetpattern.UnconfiguredBuildTargetParser;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.type.WritableTypeId;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.FromStringDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

public class ObjectMappers {

  // It's important to re-use these objects for perf:
  // https://github.com/FasterXML/jackson-docs/wiki/Presentation:-Jackson-Performance
  public static final ObjectReader READER;
  public static final ObjectWriter WRITER;
  /** ObjectReader that deserializes objects that had type information preserved */
  public static final ObjectReader READER_WITH_TYPE;

  /** ObjectWrite that serializes objects along with their type information */
  public static final ObjectWriter WRITER_WITH_TYPE;

  /** ObjectReader that interns custom objects on serialization, like UnconfiguredBuildTarget */
  public static final ObjectReader READER_INTERNED;

  public static <T> T readValue(Path file, Class<T> clazz) throws IOException {
    try (JsonParser parser = createParser(file)) {
      return READER.readValue(parser, clazz);
    }
  }

  public static <T> T readValue(Path file, TypeReference<T> clazz) throws IOException {
    try (JsonParser parser = createParser(file)) {
      return READER.readValue(parser, clazz);
    }
  }

  public static <T> T readValue(String json, Class<T> clazz) throws IOException {
    try (JsonParser parser = createParser(json)) {
      return READER.readValue(parser, clazz);
    }
  }

  public static <T> T readValue(String json, TypeReference<T> clazz) throws IOException {
    try (JsonParser parser = createParser(json)) {
      return READER.readValue(parser, clazz);
    }
  }

  public static JsonParser createParser(Path path) throws IOException {
    return jsonFactory.createParser(new BufferedInputStream(Files.newInputStream(path)));
  }

  public static JsonParser createParser(String json) throws IOException {
    return jsonFactory.createParser(json);
  }

  public static JsonParser createParser(byte[] json) throws IOException {
    return jsonFactory.createParser(json);
  }

  public static JsonParser createParser(InputStream stream) throws IOException {
    return jsonFactory.createParser(stream);
  }

  public static JsonParser createParser(Reader reader) throws IOException {
    return jsonFactory.createParser(reader);
  }

  public static JsonGenerator createGenerator(OutputStream stream) throws IOException {
    return jsonFactory.createGenerator(stream);
  }

  public static <T> T convertValue(Map<String, Object> map, Class<T> clazz) {
    return mapper.convertValue(map, clazz);
  }

  public static <T> Function<T, String> toJsonFunction() {
    return input -> {
      try {
        return WRITER.writeValueAsString(input);
      } catch (JsonProcessingException e) {
        throw new HumanReadableException(e, "Failed to serialize to json: " + input);
      }
    };
  }

  public static <T> Function<String, T> fromJsonFunction(Class<T> type) {
    return input -> {
      try {
        return readValue(input, type);
      } catch (IOException e) {
        throw new HumanReadableException(e, "Failed to read from json: " + input);
      }
    };
  }

  // This is mutable, and doesn't share a cache with the rest of Buck.
  // All uses of it should be removed.
  // Any new code should instead use READER or WRITER.
  public static ObjectMapper legacyCreate() {
    return create();
  }

  /**
   * Creates an {@link ObjectMapper} that allows to use objects without fields.
   *
   * @see SerializationFeature#FAIL_ON_EMPTY_BEANS
   */
  public static ObjectMapper createWithEmptyBeansPermitted() {
    ObjectMapper objectMapper = create();
    objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    return objectMapper;
  }

  // Callers must not modify (i.e. reconfigure) this ObjectMapper.
  private static final ObjectMapper mapper;
  private static final ObjectMapper mapper_interned;

  // Callers must not modify (i.e. reconfigure) this JsonFactory.
  private static final JsonFactory jsonFactory;

  static {
    mapper = create_without_type();
    mapper_interned = create_without_type_interned();
    READER = mapper.reader();
    READER_INTERNED = mapper_interned.reader();
    WRITER = mapper.writer();
    jsonFactory = mapper.getFactory();
    ObjectMapper mapper_with_type = create_with_type();
    READER_WITH_TYPE = mapper_with_type.reader();
    WRITER_WITH_TYPE = mapper_with_type.writer();
  }

  private static ObjectMapper create() {
    ObjectMapper mapper = new ObjectMapper();
    // Disable automatic flush() after mapper.write() call, because it is usually unnecessary,
    // and it makes BufferedOutputStreams to be useless
    mapper.disable(SerializationFeature.FLUSH_AFTER_WRITE_VALUE);
    mapper.setSerializationInclusion(Include.NON_ABSENT);
    mapper.configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, true);
    // Add support for serializing Guava collections.
    mapper.registerModule(new GuavaModule());
    mapper.registerModule(new Jdk8Module());
    mapper.registerModule(new KotlinModule());

    // With some version of Jackson JDK8 module, it starts to serialize Path objects using
    // getURI() function, this results for serialized paths to be absolute paths with 'file:///'
    // prefix. That does not work well with custom filesystems that Buck uses. Following hack
    // restores legacy behavior to serialize Paths using toString().
    SimpleModule pathModule = new SimpleModule("PathToString");

    /**
     * Custom Path serializer that serializes using {@link Object#toString()} method and also
     * translates all {@link Path} implementations to use generic base type
     */
    class PathSerializer<P> extends ToStringSerializer {
      private final Class<P> clazz;

      public PathSerializer(Class<P> clazz) {
        super(clazz);
        this.clazz = clazz;
      }

      @Override
      public void serializeWithType(
          Object value, JsonGenerator g, SerializerProvider provider, TypeSerializer typeSer)
          throws IOException {
        WritableTypeId typeIdDef =
            typeSer.writeTypePrefix(g, typeSer.typeId(value, clazz, JsonToken.VALUE_STRING));
        serialize(value, g, provider);
        typeSer.writeTypeSuffix(g, typeIdDef);
      }
    }

    pathModule.addSerializer(Path.class, new PathSerializer<>(Path.class));
    pathModule.addSerializer(AbsPath.class, new PathSerializer<>(AbsPath.class));
    pathModule.addSerializer(RelPath.class, new PathSerializer<>(RelPath.class));

    /** Deserialized for Path-like objects. */
    class PathDeserializer<P> extends FromStringDeserializer<P> {

      private final Function<String, P> get;
      private final Supplier<P> empty;

      public PathDeserializer(Class<P> clazz, Function<String, P> get, Supplier<P> empty) {
        super(clazz);
        this.get = get;
        this.empty = empty;
      }

      @Override
      protected P _deserialize(String value, DeserializationContext ctxt) {
        return get.apply(value);
      }

      @Override
      protected P _deserializeFromEmptyString() {
        // by default it returns null but we want empty Path
        return empty.get();
      }
    }

    pathModule.addDeserializer(
        Path.class, new PathDeserializer<>(Path.class, Paths::get, () -> Paths.get("")));
    pathModule.addDeserializer(
        AbsPath.class, new PathDeserializer<>(AbsPath.class, AbsPath::get, () -> AbsPath.get("")));
    pathModule.addDeserializer(
        RelPath.class, new PathDeserializer<>(RelPath.class, RelPath::get, () -> RelPath.get("")));
    mapper.registerModule(pathModule);

    return mapper;
  }

  private static ObjectMapper create_without_type() {
    ObjectMapper mapper = create();
    return addCustomModules(mapper, false);
  }

  private static ObjectMapper create_without_type_interned() {
    ObjectMapper mapper = create();
    return addCustomModules(mapper, true);
  }

  private static ObjectMapper addCustomModules(ObjectMapper mapper, boolean intern) {
    // with this mixin UnconfiguredTargetNode properties are flattened with
    // UnconfiguredTargetNodeWithDeps
    // properties
    // for prettier view. It only works for non-typed serialization.
    mapper.addMixIn(
        UnconfiguredTargetNodeWithDeps.class,
        UnconfiguredTargetNodeWithDeps.UnconfiguredTargetNodeWithDepsUnwrappedMixin.class);

    // Serialize and deserialize UnconfiguredBuildTarget as string
    SimpleModule buildTargetModule = new SimpleModule("BuildTarget");
    buildTargetModule.addSerializer(UnconfiguredBuildTarget.class, new ToStringSerializer());
    buildTargetModule.addDeserializer(
        UnconfiguredBuildTarget.class,
        new FromStringDeserializer<UnconfiguredBuildTarget>(UnconfiguredBuildTarget.class) {
          @Override
          protected UnconfiguredBuildTarget _deserialize(
              String value, DeserializationContext ctxt) {
            return UnconfiguredBuildTargetParser.parse(value, intern);
          }
        });
    mapper.registerModule(buildTargetModule);
    mapper.registerModule(forwardRelativePathModule());
    return mapper;
  }

  private static SimpleModule forwardRelativePathModule() {
    SimpleModule module = new SimpleModule();
    module.addSerializer(ForwardRelativePath.class, new ToStringSerializer());
    module.addDeserializer(
        ForwardRelativePath.class,
        new FromStringDeserializer<ForwardRelativePath>(ForwardRelativePath.class) {
          @Override
          protected ForwardRelativePath _deserialize(String value, DeserializationContext ctxt)
              throws IOException {
            return ForwardRelativePath.of(value);
          }

          @Override
          protected ForwardRelativePath _deserializeFromEmptyString() throws IOException {
            return ForwardRelativePath.EMPTY;
          }
        });
    return module;
  }

  private static ObjectMapper create_with_type() {
    return create().enableDefaultTyping();
  }

  private ObjectMappers() {}
}
