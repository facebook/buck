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

package com.facebook.buck.parser.config;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.parser.api.Syntax;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

/** Describes mapping from build file path to build file syntax. */
public class DefaultBuildFileSyntaxMapping {
  private final ImmutableList<Pair<ForwardRelPath, Syntax>> syntaxByPrefix;
  private final Syntax defaultSyntax;

  DefaultBuildFileSyntaxMapping(
      ImmutableList<Pair<ForwardRelPath, Syntax>> syntaxByPrefix, Syntax defaultSyntax) {
    this.syntaxByPrefix = syntaxByPrefix;
    this.defaultSyntax = defaultSyntax;
  }

  /** Get trivial mapping or none if mapping is not trivial. */
  public Optional<Syntax> getOnlySyntax() {
    if (syntaxByPrefix.isEmpty()) {
      return Optional.of(defaultSyntax);
    } else {
      return Optional.empty();
    }
  }

  /** Any or mappings include given syntax. */
  public boolean canHaveSyntax(Syntax syntax) {
    return syntax == defaultSyntax
        || syntaxByPrefix.stream().anyMatch(p -> p.getSecond() == syntax);
  }

  /** Find syntax for given file path. */
  public Syntax syntaxForPath(ForwardRelPath path) {
    for (Pair<ForwardRelPath, Syntax> mapping : syntaxByPrefix) {
      if (path.startsWith(mapping.getFirst())) {
        return mapping.getSecond();
      }
    }
    return defaultSyntax;
  }

  /** Parse buckconfig value. */
  public static DefaultBuildFileSyntaxMapping parse(String string, Syntax defaultSyntax) {
    // The grammar is:
    // value  ::= [entry {',' entry}]
    // entry  ::= prefix '=>' syntax
    // syntax ::= 'PYTHON_DSL' | 'SKYLARK'

    String[] parts = string.split(",");

    ImmutableList<Pair<ForwardRelPath, Syntax>> mapping =
        Arrays.stream(parts)
            .flatMap(
                s -> {
                  s = s.trim();
                  if (s.isEmpty()) {
                    return Stream.empty();
                  }
                  String[] pathToSyntax = s.split("=>", 2);
                  if (pathToSyntax.length != 2) {
                    throw new HumanReadableException(
                        "wrong syntax mapping, expecting each rule to be"
                            + " an arrow-separated ('=>') pair: "
                            + string);
                  }
                  return Stream.of(
                      new Pair<>(
                          ForwardRelPath.of(pathToSyntax[0].trim()),
                          Syntax.parseOrThrowHumanReadableException(pathToSyntax[1].trim())));
                })
            .collect(ImmutableList.toImmutableList());

    return new DefaultBuildFileSyntaxMapping(mapping, defaultSyntax);
  }

  /** Constructor for trivial mapping. */
  public static DefaultBuildFileSyntaxMapping ofOnly(Syntax syntax) {
    return new DefaultBuildFileSyntaxMapping(ImmutableList.of(), syntax);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    DefaultBuildFileSyntaxMapping that = (DefaultBuildFileSyntaxMapping) o;

    if (!syntaxByPrefix.equals(that.syntaxByPrefix)) {
      return false;
    }
    return defaultSyntax == that.defaultSyntax;
  }

  @Override
  public int hashCode() {
    int result = syntaxByPrefix.hashCode();
    result = 31 * result + defaultSyntax.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return "DefaultBuildFileSyntaxMapping{"
        + "syntaxByPrefix="
        + syntaxByPrefix
        + ", defaultSyntax="
        + defaultSyntax
        + '}';
  }
}
