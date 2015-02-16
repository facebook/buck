/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.dalvik.firstorder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class FirstOrderTest {

  private static final Function<Class<?>, ClassNode> CLASS_NODE_FROM_CLASS =
      new Function<Class<?>, ClassNode>() {
        @Override
        public ClassNode apply(Class<?> input) {
          return loadClassNode(input);
        }
      };

  private static final Function<Class<?>, Type> TYPE_FROM_CLASS = new Function<Class<?>, Type>() {
    @Override
    public Type apply(Class<?> input) {
      return Type.getType(input);
    }
  };

  private static final ImmutableList<Class<?>> KNOWN_TYPES = ImmutableList.of(
      DependencyEnum.class,
      AnnotationDependency.class,
      DependencyInterface.class,
      DependencyBase.class,
      Dependency.class,
      DerivedInterface.class,
      Derived.class);

  private static final ImmutableList<ClassNode> KNOWN_CLASS_NODES =
      FluentIterable.from(KNOWN_TYPES)
          .transform(CLASS_NODE_FROM_CLASS)
          .toList();

  enum DependencyEnum { DEFAULT }
  @Retention(RetentionPolicy.RUNTIME)
  @interface AnnotationDependency {
    DependencyEnum dependencyEnum() default DependencyEnum.DEFAULT;
  }
  interface DependencyInterface {}
  static class DependencyBase {}
  static class Dependency extends DependencyBase implements DependencyInterface {}
  interface DerivedInterface extends DependencyInterface {}
  static class Derived implements DerivedInterface {}

  @Test
  public void testHasBase() {
    class TestClass extends Dependency {
    }

    DependencyCheck.checkThat(TestClass.class)
        .doesNotDependOn(DependencyEnum.class)
        .doesNotDependOn(AnnotationDependency.class)
        .dependsOn(DependencyInterface.class)
        .dependsOn(DependencyBase.class)
        .dependsOn(Dependency.class)
        .dependsOn(TestClass.class);
  }

  @Test
  public void testHasField() {
    class TestClass {
      @SuppressWarnings("unused")
      Dependency a;
    }

    DependencyCheck.checkThat(TestClass.class)
        .doesNotDependOn(DependencyEnum.class)
        .doesNotDependOn(AnnotationDependency.class)
        .dependsOn(DependencyInterface.class)
        .dependsOn(DependencyBase.class)
        .dependsOn(Dependency.class)
        .dependsOn(TestClass.class);
  }

  @Test
  public void testHasParameter() {
    class TestClass {
      @SuppressWarnings("unused")
      void doSomething(Dependency dependency) {
      }
    }

    DependencyCheck.checkThat(TestClass.class)
        .doesNotDependOn(DependencyEnum.class)
        .doesNotDependOn(AnnotationDependency.class)
        .dependsOn(DependencyInterface.class)
        .dependsOn(DependencyBase.class)
        .dependsOn(Dependency.class)
        .dependsOn(TestClass.class);
  }

  @Test
  public void testHasReturnType() {
    class TestClass {
      @SuppressWarnings("unused")
      Dependency doSomething() {
        return null;
      }
    }

    DependencyCheck.checkThat(TestClass.class)
        .doesNotDependOn(DependencyEnum.class)
        .doesNotDependOn(AnnotationDependency.class)
        .dependsOn(DependencyInterface.class)
        .dependsOn(DependencyBase.class)
        .dependsOn(Dependency.class)
        .dependsOn(TestClass.class);
  }

  @Test
  public void testHasAnnotation() {
    class TestClass {
      @AnnotationDependency()
      int x;
    }

    DependencyCheck.checkThat(TestClass.class)
        .doesNotDependOn(DependencyEnum.class)
        .doesNotDependOn(AnnotationDependency.class)
        .doesNotDependOn(DependencyInterface.class)
        .doesNotDependOn(DependencyBase.class)
        .doesNotDependOn(Dependency.class)
        .dependsOn(TestClass.class);
  }

  @Test
  public void testHasAnnotationWithEnumProperty() {
    class TestClass {
      @AnnotationDependency(dependencyEnum = DependencyEnum.DEFAULT)
      int x;
    }

    DependencyCheck.checkThat(TestClass.class)
        .dependsOn(DependencyEnum.class)
        .doesNotDependOn(AnnotationDependency.class)
        .doesNotDependOn(DependencyInterface.class)
        .doesNotDependOn(DependencyBase.class)
        .doesNotDependOn(Dependency.class)
        .dependsOn(TestClass.class);
  }

  @Test
  public void testInterfaceInheritance() {
    DependencyCheck.checkThat(Derived.class)
        .dependsOn(Derived.class)
        .dependsOn(DerivedInterface.class)
        .dependsOn(DependencyInterface.class);
  }

  // Interestingly, observedDependencies includes DependencyInterface in
  // testInterfaceInheritance, but not in this test.  So this verifies more
  // completely that we are including the transitive closure of implemented
  // interfaces.
  @Test
  public void testInterfaceInheritanceWithField() {
    class TestClass {
      @SuppressWarnings("unused")
      Derived x;
    }
    DependencyCheck.checkThat(TestClass.class)
        .dependsOn(TestClass.class)
        .dependsOn(Derived.class)
        .dependsOn(DerivedInterface.class)
        .dependsOn(DependencyInterface.class);
  }

  private static ImmutableSet<String> getFirstOrderDependencies(
      Iterable<? extends Class<?>> types) {
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();

    FirstOrderHelper.addTypesAndDependencies(
        FluentIterable.from(types).transform(TYPE_FROM_CLASS),
        loadAndMergeClasses(types, KNOWN_CLASS_NODES),
        builder);

    return builder.build();
  }

  private static ImmutableList<ClassNode> loadAndMergeClasses(
      Iterable<? extends Class<?>> classes,
      Iterable<ClassNode> alreadyLoaded) {
    ImmutableList.Builder<ClassNode> builder = ImmutableList.builder();

    builder.addAll(alreadyLoaded);
    builder.addAll(FluentIterable.from(classes).transform(CLASS_NODE_FROM_CLASS));

    return builder.build();
  }

  private static ClassNode loadClassNode(Class<?> input) {
    try {
      ClassReader reader = new ClassReader(input.getName());
      ClassNode node = new ClassNode(Opcodes.ASM4);
      reader.accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      return node;
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  private static class DependencyCheck {

    private final FluentIterable<String> typeNames;

    DependencyCheck(Iterable<String> typeNames) {
      this.typeNames = FluentIterable.from(typeNames);
    }

    static DependencyCheck checkThat(Class<?>... types) {
      return checkThat(ImmutableList.copyOf(types));
    }

    static DependencyCheck checkThat(Iterable<? extends Class<?>> types) {
      return new DependencyCheck(getFirstOrderDependencies(types));
    }

    DependencyCheck dependsOn(Class<?> type) {
      String name = Type.getType(type).getInternalName();
      assertTrue(
          "Expected name '" + name + "' not found in list: " + typeNames,
          typeNames.contains(name));
      return this;
    }

    DependencyCheck doesNotDependOn(Class<?> type) {
      String name = Type.getType(type).getInternalName();
      assertFalse(
          "Unexpected name '" + name + "' found in list: " + typeNames,
          typeNames.contains(name));
      return this;
    }
  }
}
