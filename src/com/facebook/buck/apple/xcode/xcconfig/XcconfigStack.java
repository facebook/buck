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

package com.facebook.buck.apple.xcode.xcconfig;

// This class depends on javacc-generated source code.
// Please run "ant compile-tests" if you hit undefined symbols in IntelliJ.

import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Representation of the build settings entailed by a stack of .xcconfig files
 *
 * The stack is created with the first layer to be resolved at the top.
 */
@NotThreadSafe
public final class XcconfigStack {

  private final ImmutableList<ImmutableMultimap<String, PredicatedConfigValue>> configurationStack;
  private final Map<String, ImmutableList<TokenResolution>> resolutionCache;

  private XcconfigStack(
      ImmutableList<ImmutableMultimap<String, PredicatedConfigValue>> configurationStack) {
    this.configurationStack = configurationStack;
    this.resolutionCache = Maps.newHashMap();
  }

  /**
   * Resolve the configuration stack into a flat key-value mapping. The values which are not present
   * in the stack are left as interpolations. Referencing keys which are conditioned will generate
   * an exponential number of result keys, one for each combination of seen conditions.
   *
   * E.g. given
   *
   *    A[arch=x86_64] = a1
   *    A[arch=i386] = a2
   *    B[sdk=iphone*] = b1
   *    C = $A $B
   *
   * Will result in:
   *
   *    A[arch=x86_64] = a1
   *    A[arch=i386] = a2
   *    C[arch=x86_64,sdk=iphone*] = a1 b1
   *    C[arch=i386,sdk=iphone*] = a2 b1
   *
   * @return A list of build settings resolved to the furthest extent possible.
   */
  public List<String> resolveConfigStack() {
    Collection<String> keys = collectKeys();
    List<String> lines = Lists.newArrayListWithCapacity(keys.size());

    for (String key : keys) {
      ImmutableList<TokenResolution> resolutions = resolutionCache.get(key);
      if (resolutions == null) {
        ImmutableList.Builder<TokenResolution> builder = ImmutableList.builder();
        LookupResult result = lookupRawFromLayer(key, 0).get();
        for (PredicatedConfigValue config : result.result) {
          builder.addAll(resolveSetting(
              result.layerFound, config.key, config.conditions, config.valueTokens));
        }
        resolutions = builder.build();
        resolutionCache.put(key, resolutions);
      }

      for (TokenResolution resolution : resolutions) {
        StringBuilder line = new StringBuilder();
        line.append(key);
        if (!resolution.conditions.isEmpty()) {
          line.append('[');
          line.append(Joiner.on(',').join(resolution.conditions));
          line.append(']');
        }
        line.append(" = ");
        line.append(resolution.value);
        lines.add(line.toString());
      }
    }

    return lines;
  }

  /**
   * Resolve the strong value of some tokens.
   *
   * @param currentLayer The layer in the config that the setting was found. Inherited lookups
   *     proceed from the subsequent layer. Sentinel value -1 indicates that
   *     inherited lookups should not be performed (in the case of nested
   *     interpolations).
   * @param key String key that should be treated as inherited lookups if found in interpolations.
   * @param conditions Limiting conditions. Lookups of interpolated values shall filter out
   *     declarations with conditions that conflict with these given conditions.
   * @param tokens Tokens that should be evaluated into a string.
   *
   * @return All possible values that the token list can be interpolated, with conditions in which
   *     they occur.
   */
  private List<TokenResolution> resolveSetting(
      int currentLayer,
      @Nullable String key,
      ImmutableSortedSet<Condition> conditions,
      ImmutableList<TokenValue> tokens) {

    List<TokenResolution> prefixes = Lists.newArrayList(new TokenResolution(conditions, ""));

    for (final TokenValue token : tokens) {
      switch (token.type) {
        case LITERAL:
          // Literals result in a 1-to-1 mapping of existing resolution prefixes into one where the
          // literal is appended to the prefixes.
          prefixes = Lists.transform(prefixes, new Function<TokenResolution, TokenResolution>() {
            @Override
            public TokenResolution apply(TokenResolution input) {
              return input.appendLiteral(token.literalValue);
            }
          });
          break;
        case INTERPOLATION:
          // An interpolation results in a one-to-many mapping where conditional resolved
          // interpolations are appended to each existing prefix, and its conditions intersected
          // with the existing ones.

          // Step 1. Resolve the top-level name of the setting to be interpolated, this is necessary
          // as the names themselves may contain interpolations. This step returns multiple names as
          // the nested interpolations may themselves have different conditional values.
          List<TokenResolution> resolvedNames = resolveSetting(
              /* currentLayer */ -1, /* key */ null, conditions, token.interpolationValue);

          // Step 2. Resolve the values of each possible name. Each name may have multiple
          // definitions for different conditions, these conditions are merged with the conditions
          // of the resolved name itself.
          List<TokenResolution> resolvedValues =
              Lists.newArrayListWithExpectedSize(resolvedNames.size());
          for (TokenResolution resolvedName : resolvedNames) {
            Optional<LookupResult> result;
            if (resolvedName.value.equals("SDKROOT")) {
              // SDKROOT is interpreted in a special manner by xcode, do not interpolate the value
              result = Optional.absent();
            } else if (resolvedName.value.equals("inherited") || resolvedName.value.equals(key)) {
              if (currentLayer < 0) {
                // this is a nested resolution, in which it almost always doesn't make sense to
                // reference the variable itself.
                throw new HumanReadableException(
                    "Referencing inherited value in nested interpolation");
              }
              result = lookupRawFromLayer(key, currentLayer + 1);
            } else {
              result = lookupRawFromLayer(resolvedName.value, 0);
            }

            if (result.isPresent()) {
              for (PredicatedConfigValue lookedUpName : result.get().result) {
                ImmutableSortedSet<Condition> mergedConditions =
                    mergeConditions(conditions, lookedUpName.conditions);
                if (mergedConditions == null) {
                  continue;
                }
                List<TokenResolution> resolvedValues1 = resolveSetting(
                    result.get().layerFound, result.get().key, mergedConditions,
                    lookedUpName.valueTokens);
                resolvedValues.addAll(resolvedValues1);
              }
            } else {
              resolvedValues.add(new TokenResolution(
                  resolvedName.conditions, String.format("$(%s)", resolvedName.value)));
            }
          }

          // Step 3. Append each conditional value to each conditional prefix, while intersecting
          // the conditions. This may result in no result if the conditions are in conflict.
          List<TokenResolution> newPrefixes =
              Lists.newArrayListWithExpectedSize(resolvedValues.size() * prefixes.size());
          for (TokenResolution prefix : prefixes) {
            for (TokenResolution suffix : resolvedValues) {
              ImmutableSortedSet<Condition> mergedConditions = mergeConditions(prefix.conditions, suffix.conditions);
              if (mergedConditions == null) {
                continue;
              }
              newPrefixes.add(new TokenResolution(mergedConditions, prefix.value + suffix.value));
            }
          }
          prefixes = newPrefixes;
          break;
      }
    }

    // trim whitespace from prefixes
    ImmutableList.Builder<TokenResolution> trimmedPrefixes = ImmutableList.builder();
    for (TokenResolution prefix : prefixes) {
      trimmedPrefixes.add(new TokenResolution(prefix.conditions, prefix.value.trim()));
    }
    return trimmedPrefixes.build();
  }

  private Collection<String> collectKeys() {
    ImmutableSet.Builder<String> keys = ImmutableSet.builder();
    for (ImmutableMultimap<String, PredicatedConfigValue> layer : configurationStack) {
      keys.addAll(layer.keySet());
    }
    return keys.build();
  }

  private Optional<LookupResult> lookupRawFromLayer(String key, int startingLayer) {
    for (int i = startingLayer; i < configurationStack.size(); i++) {
      ImmutableMultimap<String, PredicatedConfigValue> layer = configurationStack.get(i);
      ImmutableCollection<PredicatedConfigValue> value = layer.get(key);
      if (!value.isEmpty()) {
        return Optional.of(new LookupResult(key, i, value));
      }
    }
    return Optional.absent();
  }

  final private static Function<Condition, String> GET_KEY_FUNC =
      new Function<Condition, String>() {
        @Override
        public String apply(Condition input) {
          return input.key;
        }
      };

  /**
   * Merge two conjunctive clauses into a more refined conjunctive clause.
   *
   * @param clause1 The first conjunctive clause
   * @param clause2 The second conjunctive clause
   * @return Conjunctive clause representing the intersection of the two clauses. Null if the
   *     intersection is empty.
   *
   * @note Consider the clause as a defining a value set where the values pass every predicate in
   *     the clause, the intersection of two clauses will define a value set where values pass
   *     the predicates in both clauses. An empty predicate set thus denotes the value set of all
   *     values, since there are no predicates imposed upon them. Intersections of conflicting
   *     clauses result in an empty value set, which we represent by the predicate set being
   *     null.
   */
  @Nullable
  private static ImmutableSortedSet<Condition> mergeConditions(
      ImmutableSortedSet<Condition> clause1, ImmutableSortedSet<Condition> clause2) {
    ImmutableSortedSet.Builder<Condition> result = ImmutableSortedSet.naturalOrder();

    Multimap<String, Condition> map1 = Multimaps.index(clause1, GET_KEY_FUNC);
    Multimap<String, Condition> map2 = Multimaps.index(clause2, GET_KEY_FUNC);

    // Every combination of keys should not conflict, and the most specific key should be inserted.
    for (String key : Sets.intersection(map1.keySet(), map2.keySet())) {
      Condition best = null;
      for (Condition current : Iterables.concat(map1.get(key), map2.get(key))) {
        if (best == null) {
          best = current;
          continue;
        }
        best = best.narrow(current);
        if (best == null) {
          return null;
        }
      }
      result.add(best);
    }

    // Keys in the symmetric difference are simply propagated.
    for (String key : Sets.difference(map1.keySet(), map2.keySet())) {
      result.addAll(map1.get(key));
    }
    for (String key : Sets.difference(map2.keySet(), map1.keySet())) {
      result.addAll(map2.get(key));
    }

    return result.build();
  }

  // --- Constructing a stack

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private final ImmutableList.Builder<ImmutableMultimap<String, PredicatedConfigValue>> stack;
    private ListMultimap<String, PredicatedConfigValue> currentLayer;

    public Builder() {
      stack = ImmutableList.builder();
      currentLayer = ArrayListMultimap.create();
    }

    public void pushLayer() {
      stack.add(ImmutableMultimap.copyOf(currentLayer));
      currentLayer = ArrayListMultimap.create();
    }

    public void addSettingsFromFile(ProjectFilesystem filesystem, Path xcconfigPath) {
      ImmutableList<PredicatedConfigValue> settings;
      try {
        settings = XcconfigParser.parse(filesystem, xcconfigPath);
      } catch (ParseException e) {
        throw new RuntimeException("Failed to add xcconfig settings from file", e);
      }
      for (PredicatedConfigValue setting : settings) {
        addSetting(setting);
      }
    }

    public void addSetting(String keyCond, String value) {
      try {
        addSetting(XcconfigParser.parseSetting(String.format("%s = %s", keyCond, value)));
      } catch (ParseException e) {
        throw new HumanReadableException(e,
            "failed to parse xcconfig setting:\n" +
            "  key: %s\n" +
            "  val: %s",
            keyCond, value);
      }
    }

    private void addSetting(PredicatedConfigValue setting) {
      List<PredicatedConfigValue> conditionalSettings = currentLayer.get(setting.key);
      // traverse the list, and replace exact match on conditions
      for (ListIterator<PredicatedConfigValue> iterator = conditionalSettings.listIterator();
           iterator.hasNext();) {
        PredicatedConfigValue existingValue = iterator.next();
        if (existingValue.conditions.equals(setting.conditions)) {
          iterator.set(setting);
          return;
        }
      }
      // if there's no exact matches, add it
      conditionalSettings.add(setting);
    }

    public XcconfigStack build() {
      return new XcconfigStack(stack.build());
    }
  }

  public static class TokenResolution {
    public final ImmutableSortedSet<Condition> conditions;
    public final String value;

    public static final TokenResolution EMPTY =
        new TokenResolution(ImmutableSortedSet.<Condition>of(), "");

    public TokenResolution(ImmutableSortedSet<Condition> conditions, String value) {
      this.conditions = conditions;
      this.value = value;
    }

    public TokenResolution appendLiteral(String val) {
      return new TokenResolution(conditions, value + val);
    }

    /**
     * Append the text of a resolution, creating a new resolution that is more restrictive.
     */
    public TokenResolution appendResolution(TokenResolution resolution) {
      ImmutableSortedSet.Builder<Condition> builder = ImmutableSortedSet.naturalOrder();
      return new TokenResolution(
          builder.addAll(conditions).addAll(resolution.conditions).build(),
          value + resolution.value);
    }

    ImmutableList<TokenResolution> appendResolutions(Iterable<TokenResolution> resolutions) {
      ImmutableList.Builder<TokenResolution> builder = ImmutableList.builder();
      for (TokenResolution resolution : resolutions) {
        builder.add(appendResolution(resolution));
      }
      return builder.build();
    }
  }

  private static class LookupResult {
    public final String key;
    public final int layerFound;
    public final ImmutableCollection<PredicatedConfigValue> result;

    public LookupResult(
        String key,
        int layerFound,
        ImmutableCollection<PredicatedConfigValue> result) {
      this.key = key;
      this.layerFound = layerFound;
      this.result = result;
    }
  }
}
