/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.cli.bootstrapper;

import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItemInArray;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.hamcrest.object.HasToString.hasToString;
import static org.junit.Assert.assertThrows;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class ClassLoaderFactoryTest {

  static final String CLASS_PATH_JAR = "/classPath.jar";
  static final String EXTRA_CLASS_PATH_JAR = "/extraClassPath.jar";

  private final Map<String, String> testEnvironment = new HashMap<>();
  private final ClassLoaderFactory classLoaderFactory =
      new ClassLoaderFactory(testEnvironment::get);

  @Before
  public void setUp() {
    testEnvironment.clear();
  }

  @Test
  public void testMissingBuckClassPlath() {
    String expectedMessage = ClassLoaderFactory.BUCK_CLASSPATH + " not set";
    assertThrows(expectedMessage, RuntimeException.class, classLoaderFactory::create);
  }

  @Test
  public void testBuckClassPlath() {
    testEnvironment.put(ClassLoaderFactory.BUCK_CLASSPATH, CLASS_PATH_JAR);

    URL[] urls = ((URLClassLoader) classLoaderFactory.create()).getURLs();

    assertThat(urls, arrayWithSize(1));
    assertThat(urls, hasItemInArray(hasToString(containsString(CLASS_PATH_JAR))));
    assertThat(urls, not(hasItemInArray(hasToString(containsString(EXTRA_CLASS_PATH_JAR)))));
  }

  @Test
  public void testBuckClassPlathWithExtraClassPath() {
    testEnvironment.put(ClassLoaderFactory.BUCK_CLASSPATH, CLASS_PATH_JAR);
    testEnvironment.put(ClassLoaderFactory.EXTRA_BUCK_CLASSPATH, EXTRA_CLASS_PATH_JAR);

    URL[] urls = ((URLClassLoader) classLoaderFactory.create()).getURLs();

    assertThat(urls, arrayWithSize(2));
    assertThat(urls, hasItemInArray(hasToString(containsString(CLASS_PATH_JAR))));
    assertThat(urls, hasItemInArray(hasToString(containsString(EXTRA_CLASS_PATH_JAR))));
  }
}
