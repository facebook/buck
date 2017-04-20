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

package org.openqa.selenium.buck.javascript;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

class JavascriptDependencies {

  private Map<String, JavascriptSource> provide2Source = Maps.newHashMap();

  public void add(JavascriptSource source) {
    Preconditions.checkNotNull(source);

    for (String provide : source.getProvides()) {
      provide2Source.put(provide, source);
    }
  }

  public void addAll(Set<JavascriptSource> sources) {
    for (JavascriptSource source : sources) {
      add(source);
    }
  }

  public void amendGraph(JavascriptDependencyGraph graph, String dep) {
    Preconditions.checkNotNull(dep);

    Set<JavascriptSource> allDeps = getDeps(dep);
    for (JavascriptSource source : allDeps) {
      graph.amendGraph(source);
    }
  }

  @VisibleForTesting
  Set<JavascriptSource> getDeps(String required) {
    ImmutableSet.Builder<JavascriptSource> deps = ImmutableSet.builder();

    Queue<String> toFind = new ArrayDeque<>();
    Set<String> seen = Sets.newHashSet();
    toFind.add(required);
    while (!toFind.isEmpty()) {
      String dep = toFind.remove();
      if (seen.contains(dep)) {
        continue;
      }
      seen.add(dep);
      JavascriptSource source = provide2Source.get(dep);
      if (source == null) {
        continue;
      }
      deps.add(source);
      toFind.addAll(source.getRequires());
    }

    return deps.build();
  }

  @Override
  public String toString() {
    return "BundleOfJoy{provide2Source=" + provide2Source + '}';
  }

  public void writeTo(Writer writer) {
    try {
      ObjectMapper mapper = new ObjectMapper();
      SimpleModule module = new SimpleModule();
      module.addSerializer(Path.class, new Serializer());
      mapper.registerModule(module);
      mapper.writeValue(writer, provide2Source.values());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("unchecked")
  public static JavascriptDependencies buildFrom(String raw) {
    JavascriptDependencies joy = new JavascriptDependencies();

    try {
      Set<?> allSources = new ObjectMapper().readValue(raw, Set.class);

      for (Object source : allSources) {
        Map<String, ?> srcMap = (Map<String, ?>) source;
        JavascriptSource jsSource = new JavascriptSource(
            (String) srcMap.get("path"),
            (Collection<String>) srcMap.get("provides"),
            (Collection<String>) srcMap.get("requires"));
        joy.add(jsSource);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return joy;
  }

  private static class Serializer extends JsonSerializer<Path> {
    @Override
    public void serialize(
        Path path,
        JsonGenerator generator,
        SerializerProvider serializerProvider) throws IOException {
      generator.writeString(path.toString());
    }
  }
}
