/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.util.json;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.skylark.json.SkylarkModule;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

public class ObjectMappers {

  // It's important to re-use these objects for perf:
  // http://wiki.fasterxml.com/JacksonBestPracticesPerformance
  public static final ObjectReader READER;
  public static final ObjectWriter WRITER;

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

  // Callers must not modify (i.e. reconfigure) this ObjectMapper.
  private static final ObjectMapper mapper;

  // Callers must not modify (i.e. reconfigure) this JsonFactory.
  private static final JsonFactory jsonFactory;

  static {
    mapper = create();
    READER = mapper.reader();
    WRITER = mapper.writer();
    jsonFactory = mapper.getFactory();
  }

  private static ObjectMapper create() {
    ObjectMapper mapper = new ObjectMapper();
    // Disable automatic flush() after mapper.write() call, because it is usually unnecessary,
    // and it makes BufferedOutputStreams to be useless
    mapper.disable(SerializationFeature.FLUSH_AFTER_WRITE_VALUE);
    mapper.setSerializationInclusion(JsonInclude.Include.NON_ABSENT);
    mapper.configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, true);
    // Add support for serializing Guava collections.
    mapper.registerModule(new GuavaModule());
    mapper.registerModule(new Jdk8Module());
    // Add support for serializing Skylark types.
    mapper.registerModule(new SkylarkModule());
    return mapper;
  }

  private ObjectMappers() {}
}
