/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules.modern.tools;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.modern.annotations.CustomClassBehaviorTag;
import com.facebook.buck.core.rules.modern.annotations.CustomFieldBehavior;
import com.facebook.buck.core.rules.modern.annotations.DefaultFieldSerialization;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ClassInfo;
import com.facebook.buck.rules.modern.CustomBehaviorUtils;
import com.facebook.buck.rules.modern.CustomClassSerialization;
import com.facebook.buck.rules.modern.CustomFieldInputs;
import com.facebook.buck.rules.modern.CustomFieldSerialization;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.facebook.buck.rules.modern.impl.AbstractValueVisitor;
import com.facebook.buck.rules.modern.impl.DefaultClassInfoFactory;
import com.facebook.buck.rules.modern.impl.ValueTypeInfoFactory;
import com.facebook.buck.rules.modern.impl.ValueTypeInfos.ExcludedValueTypeInfo;
import com.facebook.buck.rules.modern.tools.CachedErrors.Builder;
import com.facebook.buck.util.ErrorLogger;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * IsolationChecker can be used to inspect an action graph and get diagnostics about the node's
 * support for isolated execution.
 *
 * <p>Isolated execution requires that a rule is implemented as a ModernBuildRule and that all
 * excluded fields have custom serialization. The diagnostics will also include information about
 * required toolchains and referenced paths outside of the known cells.
 */
public class IsolationChecker {
  private final SourcePathResolver pathResolver;
  private final ImmutableMap<Path, Optional<String>> cellMap;
  private final FailureReporter reporter;
  private final Path rootCellPath;

  /** IsolationChecker reports diagnostics through a FailureReporter. */
  public interface FailureReporter {
    void reportNotMbr(BuildRule instance);

    void reportSerializationFailure(BuildRule instance, String crumbs, String message);

    void reportAbsolutePath(BuildRule instance, String crumbs, Path path);

    void reportSuccess(BuildRule instance);
  }

  public IsolationChecker(
      SourcePathRuleFinder ruleFinder, CellPathResolver cellResolver, FailureReporter reporter) {
    this.pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    this.rootCellPath = cellResolver.getCellPathOrThrow(Optional.empty());
    this.cellMap =
        cellResolver
            .getKnownRoots()
            .stream()
            .collect(ImmutableMap.toImmutableMap(root -> root, cellResolver::getCanonicalCellName));
    this.reporter = reporter;
  }

  /**
   * Serializes an object. For small objects, the full serialized format will be returned as a
   * byte[]. For larger objects, the representation will be recorded with the delegate and the hash
   * will be returned.
   */
  public void check(BuildRule instance) {
    if (!(instance instanceof ModernBuildRule)) {
      reporter.reportNotMbr(instance);
      return;
    }
    AtomicBoolean failure = new AtomicBoolean();

    String type = instance.getType();
    Buildable buildable = ((ModernBuildRule<?>) instance).getBuildable();
    String startingCrumb = String.format("%s[%s]", type, instance.getClass().getSimpleName());
    BiConsumer<String, Throwable> exceptionHandler =
        (crumbs, e) -> {
          String message =
              String.format("failed with message: %s", ErrorLogger.getUserFriendlyMessage(e));
          reporter.reportSerializationFailure(
              instance, String.format("%s.%s", startingCrumb, crumbs), message);
          failure.set(true);
        };
    BiConsumer<String, Path> pathHandler =
        (crumbs, path) -> {
          Preconditions.checkState(!isInRepo(path));
          reporter.reportAbsolutePath(
              instance, String.format("%s.%s", startingCrumb, crumbs), path);
        };

    checkSerialization(buildable).forEach(pathHandler, exceptionHandler);
    checkInputs(buildable).forEach(pathHandler, exceptionHandler);

    if (!failure.get()) {
      reporter.reportSuccess(instance);
    }
  }

  private boolean isInRepo(Path path) {
    Path cellPath = rootCellPath;
    for (Entry<Path, Optional<String>> candidate : cellMap.entrySet()) {
      if (path.startsWith(candidate.getKey())) {
        cellPath = candidate.getKey();
      }
    }
    return path.startsWith(cellPath);
  }

  private interface VisitorDelegate {
    <T> void visitField(
        Field field,
        T value,
        ValueTypeInfo<T> valueTypeInfo,
        Optional<CustomFieldBehavior> behavior,
        Visitor fieldVisitor);

    <T extends AddsToRuleKey> void check(T value, ClassInfo<T> classInfo, Visitor dynamicVisitor);

    Iterable<Path> visitSourcePath(SourcePath value);

    Iterable<Path> visitPath(Path value);
  }

  private class SerializationVisitorDelegate implements VisitorDelegate {
    @Override
    public <T> void visitField(
        Field field,
        T value,
        ValueTypeInfo<T> valueTypeInfo,
        Optional<CustomFieldBehavior> behavior,
        Visitor fieldVisitor) {
      if (behavior.isPresent()) {
        if (CustomBehaviorUtils.get(behavior.get(), DefaultFieldSerialization.class).isPresent()) {
          @SuppressWarnings("unchecked")
          ValueTypeInfo<T> typeInfo =
              (ValueTypeInfo<T>)
                  ValueTypeInfoFactory.forTypeToken(TypeToken.of(field.getGenericType()));
          typeInfo.visit(value, fieldVisitor);
          return;
        }

        Optional<?> serializerTag =
            CustomBehaviorUtils.get(behavior.get(), CustomFieldSerialization.class);
        if (serializerTag.isPresent()) {
          @SuppressWarnings("unchecked")
          CustomFieldSerialization<T> customSerializer =
              (CustomFieldSerialization<T>) serializerTag.get();
          customSerializer.serialize(value, fieldVisitor);
          return;
        }
      }

      if (valueTypeInfo instanceof ExcludedValueTypeInfo) {
        throw new HumanReadableException(
            "Can't serialize excluded field %s with type %s (instance type %s)",
            field.getName(),
            field.getGenericType(),
            value == null ? "<null>" : value.getClass().getName());
      }

      valueTypeInfo.visit(value, fieldVisitor);
    }

    @Override
    public <T extends AddsToRuleKey> void check(
        T value, ClassInfo<T> classInfo, Visitor dynamicVisitor) {
      Optional<CustomClassBehaviorTag> serializerTag =
          CustomBehaviorUtils.getBehavior(value.getClass(), CustomClassSerialization.class);
      if (serializerTag.isPresent()) {
        @SuppressWarnings("unchecked")
        CustomClassSerialization<T> customSerializer =
            (CustomClassSerialization<T>) serializerTag.get();
        customSerializer.serialize(value, dynamicVisitor);
      } else {
        classInfo.visit(value, dynamicVisitor);
      }
    }

    @Override
    public Iterable<Path> visitSourcePath(SourcePath value) {
      if (value instanceof BuildTargetSourcePath) {
        return ImmutableList.of();
      }
      return ImmutableList.of(pathResolver.getAbsolutePath(value));
    }

    @Override
    public Iterable<Path> visitPath(Path value) {
      if (value.isAbsolute()) {
        return ImmutableList.of(value);
      }
      return ImmutableList.of();
    }
  }

  /**
   * Used to cache the paths reachable through a given path (i.e. via directory contents/symlink
   * references.
   */
  private Map<Path, Iterable<Path>> inputsCache = new HashMap<>();

  private interface ErrorHandler {
    void registerException(String crumbs, Throwable t);

    void registerPath(String crumbs, Path path);

    void registerReference(String crumbs, AddsToRuleKey reference);
  }

  private Map<AddsToRuleKey, CachedErrors> serializationCheckResultsCache = new HashMap<>();
  private Map<AddsToRuleKey, CachedErrors> inputsCheckResultsCache = new HashMap<>();

  private CachedErrors checkSerialization(AddsToRuleKey instance) {
    return checkImpl(
        instance,
        serializationCheckResultsCache,
        this::checkSerialization,
        new SerializationVisitorDelegate());
  }

  private CachedErrors checkInputs(AddsToRuleKey instance) {
    return checkImpl(
        instance, inputsCheckResultsCache, this::checkInputs, new InputsVisitorDelegate());
  }

  private CachedErrors checkImpl(
      AddsToRuleKey instance,
      Map<AddsToRuleKey, CachedErrors> resultsCache,
      Function<AddsToRuleKey, CachedErrors> referenceHandler,
      VisitorDelegate delegate) {
    CachedErrors results = resultsCache.get(instance);
    if (results == null) {
      Builder builder = CachedErrors.builder();
      Visitor visitor =
          new Visitor(
              "",
              delegate,
              new ErrorHandler() {
                @Override
                public void registerException(String crumbs, Throwable t) {
                  builder.addExceptions(new Pair<>(crumbs, t));
                }

                @Override
                public void registerPath(String crumbs, Path path) {
                  builder.addPaths(new Pair<>(crumbs, path));
                }

                @Override
                public void registerReference(String crumbs, AddsToRuleKey reference) {
                  builder.addReferences(new Pair<>(crumbs, referenceHandler.apply(reference)));
                }
              });
      visitor.check(instance);
      results = builder.build();
      resultsCache.put(instance, results);
    }
    return results;
  }

  private class InputsVisitorDelegate implements VisitorDelegate {
    @Override
    public <T> void visitField(
        Field field,
        T value,
        ValueTypeInfo<T> valueTypeInfo,
        Optional<CustomFieldBehavior> behavior,
        Visitor fieldVisitor) {
      if (behavior.isPresent()) {
        Optional<?> serializerTag =
            CustomBehaviorUtils.get(behavior.get(), CustomFieldInputs.class);
        if (serializerTag.isPresent()) {
          @SuppressWarnings("unchecked")
          CustomFieldInputs<T> customFieldInputs = (CustomFieldInputs<T>) serializerTag.get();
          customFieldInputs.getInputs(value, fieldVisitor::visitSourcePath);
          return;
        }
      }
      valueTypeInfo.visit(value, fieldVisitor);
    }

    @Override
    public <T extends AddsToRuleKey> void check(
        T value, ClassInfo<T> classInfo, Visitor dynamicVisitor) {
      classInfo.visit(value, dynamicVisitor);
    }

    @Override
    public Iterable<Path> visitSourcePath(SourcePath value) {
      if (value instanceof BuildTargetSourcePath) {
        return ImmutableList.of();
      }
      ImmutableList.Builder<Path> builder = ImmutableList.builder();
      handlePath(pathResolver.getAbsolutePath(value), builder::add);
      return builder.build();
    }

    @Override
    public Iterable<Path> visitPath(Path value) {
      // Raw paths are not considered inputs.
      return ImmutableList.of();
    }

    void handlePath(Path path, Consumer<Path> consumer) {
      inputsCache
          .computeIfAbsent(
              path,
              ignored -> {
                ImmutableList.Builder<Path> builder = ImmutableList.builder();
                if (isInRepo(path)) {
                  try {
                    if (Files.isSymbolicLink(path)) {
                      handlePath(
                          path.getParent().resolve(Files.readSymbolicLink(path)), builder::add);
                    } else if (Files.isDirectory(path)) {
                      try (Stream<Path> list = Files.list(path)) {
                        list.forEach(child -> handlePath(child, consumer));
                      }
                    }
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                } else {
                  builder.add(path);
                }
                return builder.build();
              })
          .forEach(consumer::accept);
    }
  }

  private class Visitor extends AbstractValueVisitor<RuntimeException> {
    private final String breadcrumbs;
    private final ErrorHandler handler;
    private final VisitorDelegate delegate;

    public Visitor(String crumbs, VisitorDelegate delegate, ErrorHandler handler) {
      this.delegate = delegate;
      this.breadcrumbs = crumbs;
      this.handler = handler;
    }

    @Override
    public <T> void visitField(
        Field field,
        T value,
        ValueTypeInfo<T> valueTypeInfo,
        Optional<CustomFieldBehavior> behavior) {
      Visitor fieldVisitor =
          new Visitor(String.format("%s.%s", breadcrumbs, field.getName()), delegate, handler);
      try {
        delegate.visitField(field, value, valueTypeInfo, behavior, fieldVisitor);
      } catch (Throwable e) {
        handler.registerException(breadcrumbs, e);
      }
    }

    public void check(AddsToRuleKey instance) {
      try {
        delegate.check(instance, DefaultClassInfoFactory.forInstance(instance), this);
      } catch (Exception e) {
        handler.registerException(breadcrumbs, e);
      }
    }

    @Override
    public <T extends AddsToRuleKey> void visitDynamic(T value, ClassInfo<T> classInfo) {
      handler.registerReference(
          String.format("%s[%s]", breadcrumbs, value.getClass().getSimpleName()), value);
    }

    @Override
    public void visitSourcePath(SourcePath value) {
      delegate
          .visitSourcePath(value)
          .forEach(
              path -> {
                if (!isInRepo(path)) {
                  handler.registerPath(breadcrumbs, path);
                }
              });
    }

    @Override
    public void visitPath(Path value) {
      delegate
          .visitPath(value)
          .forEach(
              path -> {
                if (!isInRepo(path)) {
                  handler.registerPath(breadcrumbs, path);
                }
              });
    }

    @Override
    public void visitOutputPath(OutputPath value) {
      // nothing to do.
    }

    @Override
    protected void visitSimple(Object value) {
      // nothing to do.
    }
  }
}
