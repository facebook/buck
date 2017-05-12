/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rules.keys;

import static com.facebook.buck.rules.keys.RuleKeyHasher.Container;
import static com.facebook.buck.rules.keys.RuleKeyHasher.Wrapper;
import static com.facebook.buck.rules.keys.RuleKeyScopedHasher.ContainerScope;
import static com.facebook.buck.rules.keys.RuleKeyScopedHasher.Scope;

import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.rules.ArchiveMemberSourcePath;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.NonHashableSourcePathContainer;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SourceRoot;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * A base implementation for rule key builders.
 *
 * <p>{@link RuleKeyFactory} classes create concrete instances of this class and use them to produce
 * rule keys. Concrete implementations may tweak behavior of the builder, and at the very minimum
 * should implement {@link #setAppendableRuleKey(RuleKeyAppendable)}, and {@link
 * #setBuildRule(BuildRule)}.
 *
 * <p>This class implements {@link RuleKeyObjectSink} interface which is the primary mechanism of
 * how {@link RuleKeyFactory} and {@link RuleKeyAppendable} classes feed the builder.
 *
 * <p>Each element fed to the builder is a (key, value) pair. Keys are always simple strings,
 * typically the name of a field annotated with {@link AddToRuleKey}. Values on the other hand may
 * be complex types that are resolved recursively. For instance, a list of elements gets serialized
 * by serializing each element of the list in order, and finally serializing the list token along
 * with the length of the list. Similarly for other containers and wrappers.
 *
 * <p>There is an exception to the above rule of how containers and wrappers get serialized. Namely,
 * they only get serialized if at least one of their elements gets serialized. This is to support
 * concrete rule key builders that ignore some elements, or handle them differently. For example,
 * several concrete builders handle {@link SourcePath} elements in a special way.
 *
 * @param <RULE_KEY> - the actual type that the builder produces (e.g. {@code HashCode}).
 */
public abstract class RuleKeyBuilder<RULE_KEY> implements RuleKeyObjectSink {

  private static final Logger logger = Logger.get(RuleKeyBuilder.class);

  private final SourcePathRuleFinder ruleFinder;
  private final SourcePathResolver resolver;
  private final FileHashLoader hashLoader;
  private final CountingRuleKeyHasher<RULE_KEY> hasher;
  private final RuleKeyScopedHasher<RULE_KEY> scopedHasher;

  public RuleKeyBuilder(
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver resolver,
      FileHashLoader hashLoader,
      RuleKeyHasher<RULE_KEY> hasher) {
    this.ruleFinder = ruleFinder;
    this.resolver = resolver;
    this.hashLoader = hashLoader;
    this.hasher = new CountingRuleKeyHasher<>(hasher);
    this.scopedHasher = new RuleKeyScopedHasher<>(this.hasher);
  }

  @VisibleForTesting
  RuleKeyScopedHasher<RULE_KEY> getScopedHasher() {
    return this.scopedHasher;
  }

  public static RuleKeyHasher<HashCode> createDefaultHasher() {
    RuleKeyHasher<HashCode> hasher = new GuavaRuleKeyHasher(Hashing.sha1().newHasher());
    if (logger.isVerboseEnabled()) {
      hasher =
          new ForwardingRuleKeyHasher<HashCode, String>(hasher, new StringRuleKeyHasher()) {
            @Override
            protected void onHash(HashCode firstHash, String secondHash) {
              logger.verbose("RuleKey %s=%s", firstHash, secondHash);
            }
          };
    }
    return hasher;
  }

  @Override
  public final RuleKeyBuilder<RULE_KEY> setReflectively(String key, @Nullable Object val) {
    try (Scope keyScope = scopedHasher.keyScope(key)) {
      return setReflectively(val);
    }
  }

  /** Recursively serializes the value. Serialization of the key is handled outside. */
  protected RuleKeyBuilder<RULE_KEY> setReflectively(@Nullable Object val) {
    if (val instanceof RuleKeyAppendable) {
      return setAppendableRuleKey((RuleKeyAppendable) val);
    }

    if (val instanceof BuildRule) {
      return setBuildRule((BuildRule) val);
    }

    if (val instanceof Supplier) {
      try (Scope containerScope = scopedHasher.wrapperScope(Wrapper.SUPPLIER)) {
        Object newVal = ((Supplier<?>) val).get();
        return setReflectively(newVal);
      }
    }

    if (val instanceof Optional) {
      Object o = ((Optional<?>) val).orElse(null);
      try (Scope wraperScope = scopedHasher.wrapperScope(Wrapper.OPTIONAL)) {
        return setReflectively(o);
      }
    }

    if (val instanceof Either) {
      Either<?, ?> either = (Either<?, ?>) val;
      if (either.isLeft()) {
        try (Scope wraperScope = scopedHasher.wrapperScope(Wrapper.EITHER_LEFT)) {
          return setReflectively(either.getLeft());
        }
      } else {
        try (Scope wraperScope = scopedHasher.wrapperScope(Wrapper.EITHER_RIGHT)) {
          return setReflectively(either.getRight());
        }
      }
    }

    // Check to see if we're dealing with a collection of some description.
    // Note {@link java.nio.file.Path} implements "Iterable", so we explicitly exclude it here.
    if (val instanceof Iterable && !(val instanceof Path)) {
      try (ContainerScope containerScope = scopedHasher.containerScope(Container.LIST)) {
        for (Object element : (Iterable<?>) val) {
          try (Scope elementScope = containerScope.elementScope()) {
            setReflectively(element);
          }
        }
        return this;
      }
    }

    if (val instanceof Iterator) {
      Iterator<?> iterator = (Iterator<?>) val;
      try (ContainerScope containerScope = scopedHasher.containerScope(Container.LIST)) {
        while (iterator.hasNext()) {
          try (Scope elementScope = containerScope.elementScope()) {
            setReflectively(iterator.next());
          }
        }
      }
      return this;
    }

    if (val instanceof Map) {
      if (!(val instanceof SortedMap || val instanceof ImmutableMap)) {
        logger.warn(
            "Adding an unsorted map to the rule key. "
                + "Expect unstable ordering and caches misses: %s",
            val);
      }
      try (ContainerScope containerScope = scopedHasher.containerScope(Container.MAP)) {
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) val).entrySet()) {
          try (Scope elementScope = containerScope.elementScope()) {
            setReflectively(entry.getKey());
          }
          try (Scope elementScope = containerScope.elementScope()) {
            setReflectively(entry.getValue());
          }
        }
      }
      return this;
    }

    if (val instanceof Path) {
      throw new HumanReadableException(
          "It's not possible to reliably disambiguate Paths. They are disallowed from rule keys");
    }

    if (val instanceof SourcePath) {
      try {
        return setSourcePath((SourcePath) val);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    if (val instanceof NonHashableSourcePathContainer) {
      SourcePath sourcePath = ((NonHashableSourcePathContainer) val).getSourcePath();
      return setNonHashingSourcePath(sourcePath);
    }

    if (val instanceof SourceWithFlags) {
      SourceWithFlags source = (SourceWithFlags) val;
      try (ContainerScope containerScope = scopedHasher.containerScope(Container.TUPLE)) {
        try (Scope elementScope = containerScope.elementScope()) {
          setSourcePath(source.getSourcePath());
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        try (Scope elementScope = containerScope.elementScope()) {
          setReflectively(source.getFlags());
        }
      }
      return this;
    }

    return setSingleValue(val);
  }

  /**
   * Implementations should ask their factories to compute the rule key for the {@link BuildRule}
   * and call {@link #setBuildRuleKey(RuleKey)} on it.
   */
  protected abstract RuleKeyBuilder<RULE_KEY> setBuildRule(BuildRule rule);

  /** To be called from {@link #setBuildRule(BuildRule)}. */
  protected final RuleKeyBuilder<RULE_KEY> setBuildRuleKey(RuleKey ruleKey) {
    try (Scope wraperScope = scopedHasher.wrapperScope(Wrapper.BUILD_RULE)) {
      return setSingleValue(ruleKey);
    }
  }

  /**
   * Implementations should ask their factories to compute the rule key for the {@link
   * RuleKeyAppendable} and call {@link #setAppendableRuleKey(RuleKey)} on it.
   */
  protected abstract RuleKeyBuilder<RULE_KEY> setAppendableRuleKey(RuleKeyAppendable appendable);

  /** To be called from {@link #setAppendableRuleKey(RuleKeyAppendable)}. */
  protected final RuleKeyBuilder<RULE_KEY> setAppendableRuleKey(RuleKey ruleKey) {
    try (Scope wraperScope = scopedHasher.wrapperScope(Wrapper.APPENDABLE)) {
      return setSingleValue(ruleKey);
    }
  }

  /**
   * Implementations may just forward to {@link #setSourcePathDirectly}. However, they will
   * typically want to handle {@link BuildTargetSourcePath} explicitly. See also {@link
   * #setSourcePathAsRule}.
   */
  protected abstract RuleKeyBuilder<RULE_KEY> setSourcePath(SourcePath sourcePath)
      throws IOException;

  /**
   * To be called from {@link #setSourcePath(SourcePath)}. Note that this implementation handles
   * {@link BuildTargetSourcePath} same as a {@link PathSourcePath} pointing to the output of that
   * target. Implementations may change this behavior by handling {@link BuildTargetSourcePath}
   * explicitly in {@link #setSourcePath(SourcePath)} instead of calling this method. See also
   * {@link #setSourcePathAsRule}.
   */
  protected final RuleKeyBuilder<RULE_KEY> setSourcePathDirectly(SourcePath sourcePath)
      throws IOException {
    if (sourcePath instanceof BuildTargetSourcePath) {
      return setPath(resolver.getFilesystem(sourcePath), resolver.getRelativePath(sourcePath));
    } else if (sourcePath instanceof PathSourcePath) {
      Path ideallyRelativePath = resolver.getIdeallyRelativePath(sourcePath);
      if (ideallyRelativePath.isAbsolute()) {
        return setPath(
            resolver.getAbsolutePath(sourcePath), resolver.getIdeallyRelativePath(sourcePath));
      } else {
        return setPath(resolver.getFilesystem(sourcePath), ideallyRelativePath);
      }
    } else if (sourcePath instanceof ArchiveMemberSourcePath) {
      return setArchiveMemberPath(
          resolver.getFilesystem(sourcePath), resolver.getRelativeArchiveMemberPath(sourcePath));
    } else {
      throw new UnsupportedOperationException(
          "Unrecognized SourcePath implementation: " + sourcePath.getClass());
    }
  }

  /**
   * To be called from {@link #setSourcePath(SourcePath)} in case {@link BuildTargetSourcePath}
   * should be handled as a build rule. This method hashes the given {@link BuildTargetSourcePath}
   * and invokes {@link #setBuildRule(BuildRule)} on the associated rule.
   */
  protected final RuleKeyBuilder<RULE_KEY> setSourcePathAsRule(BuildTargetSourcePath sourcePath) {
    try (ContainerScope containerScope = scopedHasher.containerScope(Container.TUPLE)) {
      try (Scope elementScope = containerScope.elementScope()) {
        hasher.putBuildTargetSourcePath(sourcePath);
      }
      try (Scope elementScope = containerScope.elementScope()) {
        setBuildRule(ruleFinder.getRuleOrThrow(sourcePath));
      }
    }
    return this;
  }

  // Paths get added as a combination of the file name and file hash. If the path is absolute
  // then we only include the file name (assuming that it represents a tool of some kind
  // that's being used for compilation or some such). This does mean that if a user renames a
  // file without changing the contents, we have a cache miss. We're going to assume that this
  // doesn't happen that often in practice.
  @Override
  public RuleKeyBuilder<RULE_KEY> setPath(Path absolutePath, Path ideallyRelative)
      throws IOException {
    // TODO(simons): Enable this precondition once setPath(Path) has been removed.
    // Preconditions.checkState(absolutePath.isAbsolute());
    if (ideallyRelative.isAbsolute()) {
      logger.warn(
          "Attempting to add absolute path to rule key. Only using file name: %s", ideallyRelative);
      ideallyRelative = ideallyRelative.getFileName();
    }

    hasher.putPath(ideallyRelative, hashLoader.get(absolutePath));
    return this;
  }

  protected RuleKeyBuilder<RULE_KEY> setPath(ProjectFilesystem filesystem, Path relativePath)
      throws IOException {
    Preconditions.checkArgument(!relativePath.isAbsolute());
    hasher.putPath(relativePath, hashLoader.get(filesystem, relativePath));
    return this;
  }

  private RuleKeyBuilder<RULE_KEY> setArchiveMemberPath(
      ProjectFilesystem filesystem, ArchiveMemberPath relativeArchiveMemberPath)
      throws IOException {
    Preconditions.checkArgument(!relativeArchiveMemberPath.isAbsolute());
    hasher.putArchiveMemberPath(
        relativeArchiveMemberPath, hashLoader.get(filesystem, relativeArchiveMemberPath));
    return this;
  }

  /** Implementations may just forward to {@link #setNonHashingSourcePathDirectly}. */
  protected abstract RuleKeyBuilder<RULE_KEY> setNonHashingSourcePath(SourcePath sourcePath);

  protected final RuleKeyBuilder<RULE_KEY> setNonHashingSourcePathDirectly(SourcePath sourcePath) {
    if (sourcePath instanceof BuildTargetSourcePath) {
      hasher.putNonHashingPath(resolver.getRelativePath(sourcePath).toString());
    } else if (sourcePath instanceof PathSourcePath) {
      hasher.putNonHashingPath(resolver.getRelativePath(sourcePath).toString());
    } else if (sourcePath instanceof ArchiveMemberSourcePath) {
      hasher.putNonHashingPath(resolver.getRelativeArchiveMemberPath(sourcePath).toString());
    } else {
      throw new UnsupportedOperationException(
          "Unrecognized SourcePath implementation: " + sourcePath.getClass());
    }
    return this;
  }

  private RuleKeyBuilder<RULE_KEY> setSingleValue(@Nullable Object val) {
    if (val == null) { // Null value first
      hasher.putNull();
    } else if (val instanceof Boolean) { // JRE types
      hasher.putBoolean((boolean) val);
    } else if (val instanceof Enum) {
      hasher.putString(String.valueOf(val));
    } else if (val instanceof Number) {
      hasher.putNumber((Number) val);
    } else if (val instanceof String) {
      hasher.putString((String) val);
    } else if (val instanceof Pattern) {
      hasher.putPattern((Pattern) val);
    } else if (val instanceof BuildRuleType) { // Buck types
      hasher.putBuildRuleType((BuildRuleType) val);
    } else if (val instanceof RuleKey) {
      hasher.putRuleKey((RuleKey) val);
    } else if (val instanceof BuildTarget) {
      hasher.putBuildTarget((BuildTarget) val);
    } else if (val instanceof SourceRoot) {
      hasher.putSourceRoot((SourceRoot) val);
    } else if (val instanceof Sha1HashCode) {
      hasher.putSha1((Sha1HashCode) val);
    } else if (val instanceof byte[]) {
      hasher.putBytes((byte[]) val);
    } else {
      throw new RuntimeException("Unsupported value type: " + val.getClass());
    }
    return this;
  }

  /** Builds the rule key hash. */
  public final RULE_KEY build() {
    return hasher.hash();
  }

  /** A convenience method that builds the rule key hash and transforms it with a mapper. */
  public final <RESULT> RESULT build(Function<RULE_KEY, RESULT> mapper) {
    return mapper.apply(build());
  }
}
