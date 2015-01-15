/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.Preconditions;

import java.io.IOException;
import java.util.regex.Pattern;

@JsonSerialize(using = Flavor.FlavorSerializer.class)
public class Flavor implements Comparable<Flavor> {

  private static final Pattern VALID_FLAVOR_PATTERN = Pattern.compile("[-a-zA-Z0-9_\\.]+");

  private final String flavor;

  public Flavor(String flavor) {
    Preconditions.checkArgument(
        VALID_FLAVOR_PATTERN.matcher(flavor).matches(),
        "Invalid flavor: " + flavor);
    this.flavor = flavor;
  }

  @JsonCreator
  public static Flavor fromJson(String flavor) {
    return new Flavor(flavor);
  }

  @Override
  public int compareTo(Flavor that) {
    if (this == that) {
      return 0;
    }

    return this.flavor.compareTo(that.flavor);
  }

  @Override
  public String toString() {
    return getName();
  }

  public String getName() {
    return flavor;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof Flavor)) {
      return false;
    }

    Flavor that = (Flavor) obj;
    return this.flavor.equals(that.flavor);
  }

  @Override
  public int hashCode() {
    return flavor.hashCode();
  }

  public static class FlavorSerializer extends JsonSerializer<Flavor> {

    @Override
    public void serialize(
        Flavor flavor,
        JsonGenerator jsonGenerator,
        SerializerProvider serializerProvider) throws IOException {
      jsonGenerator.writeString(flavor.flavor);
    }
  }
}
