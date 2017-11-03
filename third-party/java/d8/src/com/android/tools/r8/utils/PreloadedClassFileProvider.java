// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.ClassFileResourceProvider;
import com.android.tools.r8.Resource;
import com.android.tools.r8.Resource.Origin;
import com.google.common.collect.Sets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Lazy Java class file resource provider based on preloaded/prebuilt context.
 */
public final class PreloadedClassFileProvider implements ClassFileResourceProvider {

  private static class ClassDescriptorOrigin extends Origin {
    private final String descriptor;

    public ClassDescriptorOrigin(String descriptor) {
      super(Origin.unknown());
      this.descriptor = descriptor;
    }

    @Override
    public String part() {
      return descriptor;
    }
  }

  private final Map<String, byte[]> content;

  private PreloadedClassFileProvider(Map<String, byte[]> content) {
    this.content = content;
  }

  @Override
  public Set<String> getClassDescriptors() {
    return Sets.newHashSet(content.keySet());
  }

  @Override
  public Resource getResource(String descriptor) {
    byte[] bytes = content.get(descriptor);
    if (bytes == null) {
      return null;
    }
    return Resource.fromBytes(
        new ClassDescriptorOrigin(descriptor), bytes, Collections.singleton(descriptor));
  }

  public static ClassFileResourceProvider fromClassData(String descriptor, byte[] data) {
    Builder builder = builder();
    builder.addResource(descriptor, data);
    return builder.build();
  }

  @Override
  public String toString() {
    return content.size() + " preloaded resources";
  }

  /**
   * Create a new empty builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private Map<String, byte[]> content = new HashMap<>();

    private Builder() {
    }

    public Builder addResource(String descriptor, byte[] bytes) {
      assert content != null;
      assert descriptor != null;
      assert bytes != null;
      assert !content.containsKey(descriptor);
      content.put(descriptor, bytes);
      return this;
    }

    public PreloadedClassFileProvider build() {
      assert content != null;
      PreloadedClassFileProvider provider = new PreloadedClassFileProvider(content);
      content = null;
      return provider;
    }
  }
}
