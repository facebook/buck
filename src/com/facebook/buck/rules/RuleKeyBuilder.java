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

package com.facebook.buck.rules;

import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
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
import java.util.Stack;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

public abstract class RuleKeyBuilder<RULE_KEY> implements RuleKeyObjectSink {

  private static final Logger logger = Logger.get(RuleKeyBuilder.class);

  private final SourcePathRuleFinder ruleFinder;
  private final SourcePathResolver resolver;
  private final FileHashLoader hashLoader;
  private final RuleKeyHasher<HashCode> hasher;
  private final RuleKeyLogger ruleKeyLogger;

  // Some RuleKey implementations may want to ignore some fields. To achieve this, in addition to
  // not hashing values of such fields, we must also not hash the keys (names) of those fields.
  // This stack is kept of (recursive) keys so that we can delay hashing the keys until we
  // decide that a value actually needs hashing. Before a value is hashed, we pop through the stack
  // hashing the keys.
  // Right now this is implemented as a stack which pops and hashes the keys in the reverse order.
  // This may potentially be a correctness issue so this should converted to a FIFO behavior.
  private Stack<String> keyStack;

  @VisibleForTesting
  protected RuleKeyBuilder(
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver resolver,
      FileHashLoader hashLoader,
      RuleKeyHasher<HashCode> hasher,
      RuleKeyLogger ruleKeyLogger) {
    this.ruleFinder = ruleFinder;
    this.resolver = resolver;
    this.hashLoader = hashLoader;
    this.hasher = hasher;
    this.ruleKeyLogger = ruleKeyLogger;
    this.keyStack = new Stack<>();
  }

  public RuleKeyBuilder(
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver resolver,
      FileHashLoader hashLoader) {
    this(
        ruleFinder,
        resolver,
        hashLoader,
        new GuavaRuleKeyHasher(Hashing.sha1().newHasher()),
        logger.isVerboseEnabled() ?
            new DefaultRuleKeyLogger() :
            new NullRuleKeyLogger());
  }


  // {@code feed} methods hash the keystack prior to hashing the actual value. Other methods should
  // never interact with RuleKeyHasher directly, but rather through these methods.
  // TODO(plamenko): this will soon get refactored to a more sound mechanism.

  private void hashKeyStack() {
    while (!keyStack.isEmpty()) {
      hasher.putKey(keyStack.pop());
    }
  }

  private void feedNull() {
    hashKeyStack();
    hasher.putNull();
  }

  private void feedBoolean(boolean val) {
    hashKeyStack();
    hasher.putBoolean(val);
  }

  private void feedNumber(Number val) {
    hashKeyStack();
    hasher.putNumber(val);
  }

  private void feedString(String val) {
    hashKeyStack();
    hasher.putString(val);
  }

  private void feedBytes(byte[] bytes) {
    hashKeyStack();
    hasher.putBytes(bytes);
  }

  private void feedPattern(Pattern pattern) {
    hashKeyStack();
    hasher.putPattern(pattern);
  }

  private void feedSha1(Sha1HashCode sha1) {
    hashKeyStack();
    hasher.putSha1(sha1);
  }

  private void feedPath(Path path, String hash) {
    hashKeyStack();
    hasher.putPath(path, hash);
  }

  private void feedArchiveMemberPath(ArchiveMemberPath path, String hash) {
    hashKeyStack();
    hasher.putArchiveMemberPath(path, hash);
  }

  private void feedNonHashingPath(String path) {
    hashKeyStack();
    hasher.putNonHashingPath(path);
  }

  private void feedSourceRoot(SourceRoot sourceRoot) {
    hashKeyStack();
    hasher.putSourceRoot(sourceRoot);
  }

  private void feedRuleKey(RuleKey ruleKey) {
    hashKeyStack();
    hasher.putRuleKey(ruleKey);
  }

  private void feedBuildRuleType(BuildRuleType buildRuleType) {
    hashKeyStack();
    hasher.putBuildRuleType(buildRuleType);
  }

  private void feedBuildTarget(BuildTarget buildTarget) {
    hashKeyStack();
    hasher.putBuildTarget(buildTarget);
  }

  private void feedBuildTargetSourcePath(BuildTargetSourcePath buildTargetSourcePath) {
    hashKeyStack();
    hasher.putBuildTargetSourcePath(buildTargetSourcePath);
  }


  protected RuleKeyBuilder<RULE_KEY> setSourcePath(SourcePath sourcePath) {
    if (sourcePath instanceof ArchiveMemberSourcePath) {
      try {
        return setArchiveMemberPath(
            resolver.getAbsoluteArchiveMemberPath(sourcePath),
            resolver.getRelativeArchiveMemberPath(sourcePath));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    if (sourcePath instanceof BuildTargetSourcePath) {
      BuildTargetSourcePath buildTargetSourcePath = (BuildTargetSourcePath) sourcePath;
      BuildRule buildRule = ruleFinder.getRuleOrThrow(buildTargetSourcePath);
      feedBuildTargetSourcePath(buildTargetSourcePath);
      return setBuildRule(buildRule);
    }

    // The original version of this expected the path to be relative, however, sometimes the
    // deprecated method returned an absolute path, which is obviously less than ideal. If we can,
    // grab the relative path to the output. We also need to hash the contents of the absolute
    // path no matter what.
    Path absolutePath = resolver.getAbsolutePath(sourcePath);
    Path ideallyRelative;
    try {
      ideallyRelative = resolver.getRelativePath(sourcePath);
    } catch (IllegalStateException e) {
      // Expected relative path was absolute. Yay.
      ideallyRelative = absolutePath;
    }
    try {
      return setPath(absolutePath, ideallyRelative);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected RuleKeyBuilder<RULE_KEY> setNonHashingSourcePath(SourcePath sourcePath) {
    String pathForKey;
    if (sourcePath instanceof ResourceSourcePath) {
      pathForKey = ((ResourceSourcePath) sourcePath).getResourceIdentifier();
    } else {
      pathForKey = resolver.getRelativePath(sourcePath).toString();
    }

    ruleKeyLogger.addNonHashingPath(pathForKey);
    feedNonHashingPath(pathForKey);
    return this;
  }

  /**
   * Implementations should ask their factories to compute the rule key for the {@link BuildRule}
   * and call {@link #setBuildRuleKey(RuleKey)} on it.
   */
  protected abstract RuleKeyBuilder<RULE_KEY> setBuildRule(BuildRule rule);

  protected final RuleKeyBuilder<RULE_KEY> setBuildRuleKey(RuleKey ruleKey) {
    return setSingleValue(ruleKey);
  }

  protected final RuleKeyBuilder<RULE_KEY> setAppendableRuleKey(String key, RuleKey ruleKey) {
    return setReflectively(key + ".appendableSubKey", ruleKey);
  }

  @Override
  public RuleKeyBuilder<RULE_KEY> setReflectively(String key, @Nullable Object val) {
    if (val instanceof RuleKeyAppendable) {
      setAppendableRuleKey(key, (RuleKeyAppendable) val);
      if (!(val instanceof BuildRule)) {
        return this;
      }

      // Explicitly fall through for BuildRule objects so we include
      // their cache keys (which may include more data than
      // appendToRuleKey() does).
    }

    // Optionals and alike types get special handling. Unwrap them if necessary and recurse.
    if (val instanceof Optional) {
      Object o = ((Optional<?>) val).orElse(null);
      return setReflectively(key, o);
    }
    if (val instanceof Either) {
      Either<?, ?> either = (Either<?, ?>) val;
      if (either.isLeft()) {
        return setReflectively(key, either.getLeft());
      } else {
        return setReflectively(key, either.getRight());
      }
    }

    int oldSize = keyStack.size();
    keyStack.push(key);
    try (RuleKeyLogger.Scope keyScope = ruleKeyLogger.pushKey(key)) {
      // Check to see if we're dealing with a collection of some description. Note
      // java.nio.file.Path implements "Iterable", so we explicitly check for Path.
      if (val instanceof Iterable && !(val instanceof Path)) {
        return setReflectively(key, ((Iterable<?>) val).iterator());
      }

      if (val instanceof Iterator) {
        Iterator<?> iterator = (Iterator<?>) val;
        while (iterator.hasNext()) {
          setReflectively(key, iterator.next());
        }
        return this;
      }

      if (val instanceof Map) {
        if (!(val instanceof SortedMap || val instanceof ImmutableMap)) {
          logger.info(
              "Adding an unsorted map to the rule key (%s). " +
                  "Expect unstable ordering and caches misses: %s",
              key,
              val);
        }
        try (RuleKeyLogger.Scope mapScope = ruleKeyLogger.pushMap()) {
          feedString("{");
          for (Map.Entry<?, ?> entry : ((Map<?, ?>) val).entrySet()) {
            try (RuleKeyLogger.Scope mapKeyScope = ruleKeyLogger.pushMapKey()) {
              setReflectively(key, entry.getKey());
            }
            feedString(" -> ");
            try (RuleKeyLogger.Scope mapValueScope = ruleKeyLogger.pushMapValue()) {
              setReflectively(key, entry.getValue());
            }
          }
          feedString("}");
        }
        return this;
      }

      if (val instanceof BuildRule) {
        return setBuildRule((BuildRule) val);
      }
      if (val instanceof Path) {
        throw new HumanReadableException(
            "It's not possible to reliably disambiguate Paths. They are disallowed from rule keys");
      }
      if (val instanceof SourcePath) {
        return setSourcePath((SourcePath) val);
      }
      if (val instanceof NonHashableSourcePathContainer) {
        SourcePath sourcePath = ((NonHashableSourcePathContainer) val).getSourcePath();
        return setNonHashingSourcePath(sourcePath);
      }
      if (val instanceof SourceWithFlags) {
        SourceWithFlags source = (SourceWithFlags) val;
        try (RuleKeyLogger.Scope scope = ruleKeyLogger.pushSourceWithFlags()) {
          setSourcePath(source.getSourcePath());
          setReflectively(key, source.getFlags());
        }
        return this;
      }

      if (val instanceof Supplier) {
        Object newVal = ((Supplier<?>) val).get();
        return setReflectively(key, newVal);
      }

      return setSingleValue(val);
    } finally {
      while (keyStack.size() > oldSize) {
        keyStack.pop();
      }
    }
  }

  // Paths get added as a combination of the file name and file hash. If the path is absolute
  // then we only include the file name (assuming that it represents a tool of some kind
  // that's being used for compilation or some such). This does mean that if a user renames a
  // file without changing the contents, we have a cache miss. We're going to assume that this
  // doesn't happen that often in practice.
  @Override
  public RuleKeyBuilder<RULE_KEY> setPath(
      Path absolutePath,
      Path ideallyRelative) throws IOException {
    // TODO(shs96c): Enable this precondition once setPath(Path) has been removed.
    // Preconditions.checkState(absolutePath.isAbsolute());
    HashCode sha1 = hashLoader.get(absolutePath);
    if (sha1 == null) {
      throw new RuntimeException("No SHA for " + absolutePath);
    }

    Path addToKey;
    if (ideallyRelative.isAbsolute()) {
      logger.warn(
          "Attempting to add absolute path to rule key. Only using file name: %s", ideallyRelative);
      addToKey = ideallyRelative.getFileName();
    } else {
      addToKey = ideallyRelative;
    }

    ruleKeyLogger.addPath(addToKey, sha1);
    feedPath(addToKey, sha1.toString());
    return this;
  }

  public RuleKeyBuilder<RULE_KEY> setArchiveMemberPath(
      ArchiveMemberPath absoluteArchiveMemberPath,
      ArchiveMemberPath relativeArchiveMemberPath) throws IOException {
    Preconditions.checkState(absoluteArchiveMemberPath.isAbsolute());
    Preconditions.checkState(!relativeArchiveMemberPath.isAbsolute());

    HashCode hash = hashLoader.get(absoluteArchiveMemberPath);
    if (hash == null) {
      throw new RuntimeException("No hash for " + absoluteArchiveMemberPath);
    }

    ArchiveMemberPath addToKey = relativeArchiveMemberPath;
    ruleKeyLogger.addArchiveMemberPath(addToKey, hash);
    feedArchiveMemberPath(addToKey, hash.toString());
    return this;
  }

  private RuleKeyBuilder<RULE_KEY> setSingleValue(@Nullable Object val) {
    if (val == null) { // Null value first
      ruleKeyLogger.addNullValue();
      feedNull();
    } else if (val instanceof Boolean) {           // JRE types
      ruleKeyLogger.addValue((boolean) val);
      feedBoolean((boolean) val);
    } else if (val instanceof Enum) {
      ruleKeyLogger.addValue((Enum<?>) val);
      feedString(String.valueOf(val));
    } else if (val instanceof Number) {
      ruleKeyLogger.addValue((Number) val);
      feedNumber((Number) val);
    } else if (val instanceof String) {
      ruleKeyLogger.addValue((String) val);
      feedString((String) val);
    } else if (val instanceof Pattern) {
      ruleKeyLogger.addValue((Pattern) val);
      feedPattern((Pattern) val);
    } else if (val instanceof BuildRuleType) {
      ruleKeyLogger.addValue((BuildRuleType) val);
      feedBuildRuleType((BuildRuleType) val);
    } else if (val instanceof RuleKey) {
      ruleKeyLogger.addValue((RuleKey) val);
      feedRuleKey((RuleKey) val);
    } else if (val instanceof BuildTarget) {
      ruleKeyLogger.addValue((BuildTarget) val);
      feedBuildTarget((BuildTarget) val);
    } else if (val instanceof SourceRoot) {
      ruleKeyLogger.addValue((SourceRoot) val);
      feedSourceRoot((SourceRoot) val);
    } else if (val instanceof Sha1HashCode) {
      // TODO(plamenko): ruleKeyLogger
      feedSha1((Sha1HashCode) val);
    } else if (val instanceof byte[]) {
      ruleKeyLogger.addValue((byte[]) val);
      feedBytes((byte[]) val);
    } else {
      throw new RuntimeException("Unsupported value type: " + val.getClass());
    }
    return this;
  }

  protected final RuleKey buildRuleKey() {
    RuleKey ruleKey = new RuleKey(hasher.hash());
    ruleKeyLogger.registerRuleKey(ruleKey);
    return ruleKey;
  }

  public abstract RULE_KEY build();

}
