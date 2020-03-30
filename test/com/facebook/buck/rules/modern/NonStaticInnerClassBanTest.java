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

package com.facebook.buck.rules.modern;

import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.ClassPath;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * NonStaticInnerClassBanTest is designed to catch and ban non-static inner classes that implement
 * certain interfaces.
 *
 * <p>The core design {@link ModernBuildRule} requires that instances of {@link Buildable} be
 * completely serializable. In order for this to be true, any class captured in the transitive
 * closure of a {@link Buildable} must also be serializable. Throughout the Buck codebase, this is
 * accomplished by classes that annotate their fields with {@link
 * com.facebook.buck.core.rulekey.AddToRuleKey} to signal serialization.
 *
 * <p>Because ModernBuildRule needs to completely enumerate and serialize all fields that are
 * transitively captured by a {@link Buildable}, it explicitly bans all fields that are not marked
 * with {@link com.facebook.buck.core.rulekey.AddToRuleKey}. Furthermore, it also bans implicit
 * links to parent classes, which the JVM automatically emits unless a class is marked `static`.
 *
 * <p>Since it has proven to be difficult to track down all of the classes that fall into the latter
 * category, this test reflects over a lot of classes in Buck and tries to make sure that there are
 * no non-static classes that implement particular interfaces that are known to cross over into
 * ModernBuildRule buildables. These tests will fail if a new class is introduced that breaks this
 * invariant.
 *
 * <p>This class explicitly checks subclasses of two interfaces:
 *
 * <ul>
 *   <li>{@link Arg}, the type of arguments to command-line tools
 *   <li>{@link Tool}, the type of command-line tools
 * </ul>
 *
 * Both of these are known to travel remotely as part of MBR execution and thus are subject to the
 * restriction that none of their subclasses may be non-static and nested.
 */
@RunWith(Parameterized.class)
public class NonStaticInnerClassBanTest {
  /**
   * The full set of classes to check. This set is derived from the classpath, so you must add Buck
   * dependencies on packages that contain classes that you want to test, otherwise they won't be in
   * the classpath!
   */
  @Parameterized.Parameters(name = "{0}")
  public static Collection<Class<?>> data() throws IOException {
    ClassPath classpath = ClassPath.from(ClassLoader.getSystemClassLoader());
    return classpath.getAllClasses().stream()
        // Exclude anything we didn't write
        .filter(info -> info.getPackageName().startsWith("com.facebook.buck"))
        // Only operate on nested classes
        .filter(info -> info.getName().contains("$"))
        .map(ClassPath.ClassInfo::load)
        // Only operate on nested classes deriving from one or more of the classes we're checking
        .filter(
            clazz ->
                bannedClasses.stream().anyMatch(bannedClass -> bannedClass.isAssignableFrom(clazz)))
        .collect(Collectors.toList());
  }

  /**
   * The set of classes for which non-static inner subclass implementations are banned. These are
   * classes that are known to cross MBR serialization boundaries. Add to this list as necessary!
   */
  private static final ImmutableSet<Class<?>> bannedClasses =
      ImmutableSet.<Class<?>>builder().add(Arg.class).add(Tool.class).build();

  private final Class<?> classToCheck;

  public NonStaticInnerClassBanTest(Class<?> classToCheck) {
    this.classToCheck = classToCheck;
  }

  @Test
  public void noAnonymousSubclassesOf() {
    for (Class<?> bannedClass : bannedClasses) {
      if (bannedClass.isAssignableFrom(classToCheck)
          && !Modifier.isStatic(classToCheck.getModifiers())) {
        Assert.fail(
            "Inner Class `"
                + classToCheck.getName()
                + "` derives from `"
                + bannedClass
                + "` but is not `static`");
      }
    }
  }
}
