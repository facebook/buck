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

package com.facebook.buck.jvm.java.plugin;

import com.facebook.buck.jvm.java.plugin.api.PluginClassLoader;
import com.facebook.buck.jvm.java.plugin.api.PluginClassLoaderFactory;
import com.facebook.buck.util.ClassLoaderCache;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import javax.annotation.Nullable;
import javax.tools.JavaCompiler;

/**
 * Loads the Buck javac plugin JAR using the {@link ClassLoader} of a particular compiler instance.
 *
 * <p>{@link com.sun.source.tree} and {@link com.sun.source.util} packages are public APIs whose
 * implementation is packaged with javac rather than in the Java runtime jar. Code that needs to
 * work with these must be loaded using the same {@link ClassLoader} as the instance of javac with
 * which it will be working.
 */
public final class PluginLoader implements PluginClassLoader {
  private static final String JAVAC_PLUGIN_JAR_RESOURCE_PATH = "javac-plugin.jar";
  private static final URL JAVAC_PLUGIN_JAR_URL = extractJavacPluginJar();

  private final ClassLoader classLoader;

  /**
   * Extracts the jar containing the Buck javac plugin and returns a URL that can be given to a
   * {@link java.net.URLClassLoader} to load it.
   */
  private static URL extractJavacPluginJar() {
    @Nullable URL resourceURL = PluginLoader.class.getResource(JAVAC_PLUGIN_JAR_RESOURCE_PATH);
    if (resourceURL == null) {
      throw new RuntimeException("Could not find javac plugin jar; Buck may be corrupted.");
    } else if ("file".equals(resourceURL.getProtocol())) {
      // When Buck is running from the repo, the jar is actually already on disk, so no extraction
      // is necessary
      return resourceURL;
    } else {
      // Running from a .pex file, extraction is required
      try (InputStream resourceStream =
          PluginLoader.class.getResourceAsStream(JAVAC_PLUGIN_JAR_RESOURCE_PATH)) {
        File tempFile = File.createTempFile("javac-plugin", ".jar");
        tempFile.deleteOnExit();
        try (OutputStream tempFileStream = new FileOutputStream(tempFile)) {
          ByteStreams.copy(resourceStream, tempFileStream);
          return tempFile.toURI().toURL();
        }
      } catch (IOException e) {
        throw new RuntimeException("Failed to extract javac plugin jar; cannot continue", e);
      }
    }
  }

  /** Returns a class loader that can be used to load classes from the compiler plugin jar. */
  public static ClassLoader getPluginClassLoader(
      ClassLoaderCache classLoaderCache, JavaCompiler.CompilationTask compiler) {
    ClassLoader compilerClassLoader = compiler.getClass().getClassLoader();
    return classLoaderCache.getClassLoaderForClassPath(
        compilerClassLoader, ImmutableList.of(JAVAC_PLUGIN_JAR_URL));
  }

  public static PluginLoader newInstance(
      ClassLoaderCache classLoaderCache, JavaCompiler.CompilationTask compiler) {
    return new PluginLoader(getPluginClassLoader(classLoaderCache, compiler));
  }

  public static PluginClassLoaderFactory newFactory(ClassLoaderCache cache) {
    return new PluginClassLoaderFactory() {
      @Override
      public PluginClassLoader getPluginClassLoader(JavaCompiler.CompilationTask task) {
        return PluginLoader.newInstance(cache, task);
      }
    };
  }

  private PluginLoader(ClassLoader classLoader) {
    this.classLoader = classLoader;
  }

  @Override
  public <T> Class<? extends T> loadClass(String name, Class<T> superclass) {
    if (classLoader == null) {
      // No log here because we logged when the plugin itself failed to load
      return null;
    }

    try {
      return Class.forName(name, false, classLoader).asSubclass(superclass);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(String.format("Failed loading %s", name), e);
    }
  }
}
