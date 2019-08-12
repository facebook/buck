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

package com.facebook.buck.jvm.java.runner.java8;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import sun.misc.Resource;
import sun.misc.URLClassPath;

/**
 * The previous iteration of FileClassPathRunner used the `UrlClassLoader#addUrl` function to append
 * URLs to the existing classloader. This disables the internal JVM cache which maps class names to
 * their location in jars (in fact, any modification of the classpath will disable the cache)
 * Without this cache, each class lookup takes linear time in the total number of jars on the
 * classpath.
 *
 * <p>For JUnit tests, Buck puts the full transitive dependency tree onto the classpath, meaning
 * that for a large codebase with lots of small modules, the number of jars can be large. This can
 * cause significant slowdowns for tests.
 *
 * <p>CachingClassPath implements a very simplistic cache where each package name is mapped to up to
 * two smaller URLClassPath instances. Both of these smaller instances are queried before falling
 * back to a linear search. In testing this reduced runtime for large classpath tests by up to 30%.
 *
 * <p>The 2-slot cache was chosen because of the common pattern for unit tests: a `java_test` and
 * the `java_library` under test will create two separate jars which share the same package name.
 */
public class CachingClassPath extends URLClassPath {
  // This size was chosen via empirical experimentation
  private static final int CHUNK_SIZE = 250;
  // Sentinel value for an empty memoization slot
  private static final int EMPTY = -1;

  List<URLClassPath> chunks = new ArrayList<>();
  Map<String, int[]> shortcut = new HashMap<>();

  public CachingClassPath(URL[] urls) {
    super(urls);

    // We break up the classpath into smaller chunks, allowing each to manage a subset of the
    // total URLs so we don't have to re-write any logic related to handling the URLs directly.
    int i = 0;
    while (i < urls.length - CHUNK_SIZE) {
      chunks.add(new URLClassPath(Arrays.copyOfRange(urls, i, i + CHUNK_SIZE)));
      i += CHUNK_SIZE;
    }
    chunks.add(new URLClassPath(Arrays.copyOfRange(urls, i, urls.length)));
  }

  @Override
  public URL findResource(String s, boolean b) {
    Resource res = lookupResource(s);
    if (res != null) {
      return res.getURL();
    } else {
      return null;
    }
  }

  @Override
  public Resource getResource(String s, boolean b) {
    return lookupResource(s);
  }

  @Override
  public Enumeration<URL> findResources(String s, boolean b) {
    return Collections.enumeration(
        lookupResources(s).stream().map(Resource::getURL).collect(Collectors.toList()));
  }

  @Override
  public Enumeration<Resource> getResources(String s, boolean b) {
    return Collections.enumeration(lookupResources(s));
  }

  /**
   * Find the resource corresponding to a given path, or null if it cannot be found. The dirname of
   * the path is used as a resource locator, and the result is memoized. If the memoized lookup
   * fails, we fall back to a linear scan. This approach respects classpath ordering by always
   * trying lower-numbered classpath entries before higher-numbered ones.
   */
  private Resource lookupResource(String path) {
    // We use the package path as the locator since this is most likely to be stable across multiple
    // files in the same package
    String packagePath = path;
    if (path.contains("/")) {
      packagePath = path.substring(0, path.lastIndexOf('/'));
    }
    Resource res = null;

    int[] guess = shortcut.get(packagePath);
    // Try and use our guess to define the resource
    if (guess != null) {
      for (int i = 0; i < guess.length && res == null && guess[i] != EMPTY; i++) {
        res = this.chunks.get(guess[i]).getResource(path);
      }
    }
    int location = EMPTY;
    // Fall back to a linear scan
    for (int i = 0; res == null && i < this.chunks.size(); i++) {
      res = this.chunks.get(i).getResource(path);
      location = i;
    }
    // Insert the new location if necessary
    if (res != null && location != EMPTY) {
      if (!shortcut.containsKey(packagePath)) {
        // We provide 2 slots for each lookup based on the assumption that test code will have
        // the same package name as the code under test, but reside in a different chunk of
        // our classpath.
        shortcut.put(packagePath, new int[] {EMPTY, EMPTY});
      }
      int[] tuple = shortcut.get(packagePath);
      // Try to insert the location into the first empty slot. If no slots are open, then
      // this insertion is a no-op.
      for (int i = 0; i < tuple.length; i++) {
        if (tuple[i] == EMPTY) {
          tuple[i] = location;
          break;
        }
      }
    }
    return res;
  }

  /**
   * Find all resources with the given name. In the future it might be more efficient to return a
   * lazy Enumeration here in the same way that the base class does.
   */
  private Collection<Resource> lookupResources(String name) {
    ArrayList<Resource> builder = new ArrayList<>();
    Enumeration<URLClassPath> chunksIter = Collections.enumeration(chunks);
    while (chunksIter.hasMoreElements()) {
      Enumeration<Resource> e = chunksIter.nextElement().getResources(name, true);
      while (e.hasMoreElements()) {
        builder.add(e.nextElement());
      }
    }
    return builder;
  }
}
