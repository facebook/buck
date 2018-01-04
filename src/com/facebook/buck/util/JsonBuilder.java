/*
 * Copyright 2017-present Facebook, Inc.
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

import com.fasterxml.jackson.core.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Collector;
import java.util.stream.DoubleStream;

/**
 * Fluent API for constructing a JSON string.
 *
 * <p>For example, {@code {"a": {"b": true, "c": [1.0,2.0,3.0]}}} can be written as:
 *
 * <pre>{@code
 * JsonBuilder.object()
 *   .addObject(
 *     "a",
 *     JsonBuilder.object()
 *       .addBoolean("b", true)
 *       .addArray("c", JsonBuilder.array().addNumber(1.0).addNumber(2.0).addNumber(3.0)))
 *   .toString();
 * }</pre>
 */
public class JsonBuilder {
  private static final char[] emptyField = new char[0];
  private static final Collector<String, ArrayBuilder, ArrayBuilder> TO_ARRAY_OF_STRINGS =
      toArray(ArrayBuilder::addString);
  private static final Collector<ArrayBuilder, ArrayBuilder, ArrayBuilder> TO_ARRAY_OF_ARRAYS =
      toArray(ArrayBuilder::addArray);
  private static final Collector<ObjectBuilder, ArrayBuilder, ArrayBuilder> TO_ARRAY_OF_OBJECTS =
      toArray(ArrayBuilder::addObject);

  /** Creates a builder for a JSON array string. */
  public static ArrayBuilder array() {
    try {
      return new ArrayBuilder();
    } catch (IOException e) {
      throw toAssertionError(e);
    }
  }

  /** Creates a builder for a JSON object string. */
  public static ObjectBuilder object() {
    try {
      return new ObjectBuilder();
    } catch (IOException e) {
      throw toAssertionError(e);
    }
  }

  public static ArrayBuilder arrayOfDoubles(DoubleStream numbers) {
    return numbers.collect(JsonBuilder::array, ArrayBuilder::addNumber, ArrayBuilder::merge);
  }

  public static Collector<String, ?, ArrayBuilder> toArrayOfStrings() {
    return TO_ARRAY_OF_STRINGS;
  }

  public static Collector<ArrayBuilder, ?, ArrayBuilder> toArrayOfArrays() {
    return TO_ARRAY_OF_ARRAYS;
  }

  public static Collector<ObjectBuilder, ?, ArrayBuilder> toArrayOfObjects() {
    return TO_ARRAY_OF_OBJECTS;
  }

  public static <T> Collector<T, ArrayBuilder, ArrayBuilder> toArray(
      BiConsumer<ArrayBuilder, T> accumulate) {
    return Collector.of(JsonBuilder::array, accumulate, ArrayBuilder::merge);
  }

  /** Builder for a JSON array string. */
  public static class ArrayBuilder extends Builder {

    private ArrayBuilder() throws IOException {
      getGenerator().writeStartArray();
    }

    /** Adds a 'null' value to the array. */
    public ArrayBuilder addNull() {
      try {
        getGenerator().writeNull();
      } catch (IOException e) {
        throw toAssertionError(e);
      }
      return this;
    }

    /** Adds a boolean value to the array. */
    public ArrayBuilder addBoolean(boolean value) {
      try {
        getGenerator().writeBoolean(value);
      } catch (IOException e) {
        throw toAssertionError(e);
      }
      return this;
    }

    /** Adds a boolean value to the array, or 'null' if not present. */
    public ArrayBuilder addBoolean(Optional<Boolean> maybeValue) {
      return maybeValue.isPresent() ? addBoolean(maybeValue.get()) : addNull();
    }

    /** Adds a numeric value to the array. */
    public ArrayBuilder addNumber(double value) {
      try {
        getGenerator().writeNumber(value);
      } catch (IOException e) {
        throw toAssertionError(e);
      }
      return this;
    }

    /** Adds a numeric value to the array, or 'null' if not present. */
    public ArrayBuilder addNumber(Optional<Double> maybeValue) {
      return maybeValue.isPresent() ? addNumber(maybeValue.get()) : addNull();
    }

    /** Adds a string to the array. */
    public ArrayBuilder addString(String value) {
      try {
        getGenerator().writeString(value);
      } catch (IOException e) {
        throw toAssertionError(e);
      }
      return this;
    }

    /** Adds a string to the array, or 'null' if not present. */
    public ArrayBuilder addString(Optional<String> maybeValue) {
      return maybeValue.isPresent() ? addString(maybeValue.get()) : addNull();
    }

    /** Adds a nested JSON object to the array. */
    public ArrayBuilder addObject(ObjectBuilder builder) {
      try {
        writeNested(builder);
      } catch (IOException e) {
        throw toAssertionError(e);
      }
      return this;
    }

    /** Adds a nested JSON array to the array. */
    public ArrayBuilder addArray(ArrayBuilder builder) {
      try {
        writeNested(builder);
      } catch (IOException e) {
        throw toAssertionError(e);
      }
      return this;
    }

    /** Adds a raw JSON value to the array. */
    public ArrayBuilder addRaw(String rawValue) {
      try {
        getGenerator().writeRawValue(rawValue);
      } catch (IOException e) {
        throw toAssertionError(e);
      }
      return this;
    }

    /** Adds a raw JSON value to the array, or 'null' if not present. */
    public ArrayBuilder addRaw(Optional<String> maybeRawValue) {
      return maybeRawValue.isPresent() ? addRaw(maybeRawValue.get()) : addNull();
    }

    /**
     * Merges two ArrayBuilders. Necessary for Collectors. This <i>should</i> never be called when
     * collecting streams, as the provided {@link Collector} instances are not concurrent.
     */
    public ArrayBuilder merge(ArrayBuilder other) {
      try {
        other.getGenerator().flush();
        if (other.getStream().size() > 1) {
          getGenerator().writeRawValue(emptyField, 0, 0);
          getGenerator().flush();
          byte[] bytes = other.getStream().toByteArray();
          getStream().write(bytes, 1, bytes.length - 1);
        }
      } catch (IOException e) {
        throw toAssertionError(e);
      }

      return this;
    }
  }

  /** Builder for a JSON object string. */
  public static class ObjectBuilder extends Builder {

    private ObjectBuilder() throws IOException {
      getGenerator().writeStartObject();
    }

    /** Adds a 'null' value to the object. */
    public ObjectBuilder addNull(String fieldName) {
      try {
        getGenerator().writeNullField(fieldName);
      } catch (IOException e) {
        throw toAssertionError(e);
      }
      return this;
    }

    /** Adds a boolean value to the object. */
    public ObjectBuilder addBoolean(String fieldName, boolean value) {
      try {
        getGenerator().writeBooleanField(fieldName, value);
      } catch (IOException e) {
        throw toAssertionError(e);
      }
      return this;
    }

    /** Adds a boolean value to the object, if present. */
    public ObjectBuilder addBoolean(String fieldName, Optional<Boolean> maybeValue) {
      return maybeValue.isPresent() ? addBoolean(fieldName, maybeValue.get()) : this;
    }

    /** Adds a numeric value to the object. */
    public ObjectBuilder addNumber(String fieldName, double value) {
      try {
        getGenerator().writeNumberField(fieldName, value);
      } catch (IOException e) {
        throw toAssertionError(e);
      }
      return this;
    }

    /** Adds a numeric value to the object, if present. */
    public ObjectBuilder addNumber(String fieldName, Optional<Double> maybeValue) {
      return maybeValue.isPresent() ? addNumber(fieldName, maybeValue.get()) : this;
    }

    /** Adds a string to the object. */
    public ObjectBuilder addString(String fieldName, String value) {
      try {
        getGenerator().writeStringField(fieldName, value);
      } catch (IOException e) {
        throw toAssertionError(e);
      }
      return this;
    }

    /** Adds a string to the object, if present. */
    public ObjectBuilder addString(String fieldName, Optional<String> maybeValue) {
      return maybeValue.isPresent() ? addString(fieldName, maybeValue.get()) : this;
    }

    /** Adds a nested JSON object to the object. */
    public ObjectBuilder addObject(String fieldName, ObjectBuilder builder) {
      try {
        getGenerator().writeFieldName(fieldName);
        writeNested(builder);
      } catch (IOException e) {
        throw toAssertionError(e);
      }
      return this;
    }

    /** Adds a nested JSON array to the object. */
    public ObjectBuilder addArray(String fieldName, ArrayBuilder builder) {
      try {
        getGenerator().writeFieldName(fieldName);
        writeNested(builder);
      } catch (IOException e) {
        throw toAssertionError(e);
      }
      return this;
    }

    /** Adds a raw JSON value to the object. */
    public ObjectBuilder addRaw(String fieldName, String rawValue) {
      try {
        getGenerator().writeFieldName(fieldName);
        getGenerator().writeRawValue(rawValue);
      } catch (IOException e) {
        throw toAssertionError(e);
      }
      return this;
    }

    /** Adds a raw JSON value to the object, if present. */
    public ObjectBuilder addRaw(String fieldName, Optional<String> maybeRawValue) {
      return maybeRawValue.isPresent() ? addRaw(fieldName, maybeRawValue.get()) : this;
    }
  }

  private abstract static class Builder {
    private final ByteArrayOutputStream stream = new ByteArrayOutputStream();
    private final JsonGenerator generator;

    protected Builder() throws IOException {
      generator = ObjectMappers.createGenerator(stream);
    }

    protected final ByteArrayOutputStream getStream() {
      return stream;
    }

    protected final JsonGenerator getGenerator() {
      return generator;
    }

    protected final void writeNested(Builder builder) throws IOException {
      generator.writeRawValue(emptyField, 0, 0); // ensure we get a separator (colon, comma)
      generator.flush(); // write out to underlying stream
      builder.generator.close(); // close, flush to stream
      builder.stream.writeTo(stream); // append raw contents
    }

    @Override
    public final String toString() {
      try {
        generator.close();
        return stream.toString(StandardCharsets.UTF_8.name());
      } catch (IOException e) {
        throw toAssertionError(e);
      }
    }
  }

  private static AssertionError toAssertionError(IOException ioException) {
    return new AssertionError("JSON operation failed", ioException);
  }

  /** Utility class -- avoid intantiation */
  private JsonBuilder() {}
}
