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

package com.facebook.buck.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ObjectMappers {

  // It's important to re-use these objects for perf:
  // http://wiki.fasterxml.com/JacksonBestPracticesPerformance
  public static final ObjectReader READER;
  public static final ObjectWriter WRITER;

  public static <T> T readValue(File file, Class<T> clazz) throws IOException {
    try (JsonParser parser = createParser(file)) {
      return READER.readValue(parser, clazz);
    }
  }

  public static <T> T readValue(File file, TypeReference<T> clazz) throws IOException {
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

  public static JsonParser createParser(File file) throws IOException {
    return jsonFactory.createParser(file);
  }

  public static JsonParser createParser(InputStream stream) throws IOException {
    return jsonFactory.createParser(stream);
  }

  public static JsonParser createParser(String json) throws IOException {
    return jsonFactory.createParser(json);
  }

  public static JsonParser createParser(byte[] json) throws IOException {
    return jsonFactory.createParser(json);
  }

  public static JsonGenerator createGenerator(OutputStream stream) throws IOException {
    return jsonFactory.createGenerator(stream);
  }

  // This is mutable, and doesn't share a cache with the rest of Buck.
  // All uses of it should be removed.
  // Any new code should instead use READER or WRITER.
  public static ObjectMapper legacyCreate() {
    return create();
  }

  // Callers must not modify (i.e. reconfigure) this JsonFactory.
  private static final JsonFactory jsonFactory;

  static {
    ObjectMapper mapper = create();
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
    // Add support for serializing Guava collections.
    mapper.registerModule(new GuavaModule());
    mapper.registerModule(new Jdk8Module());
    return mapper;
  }

  private ObjectMappers() {}
}
