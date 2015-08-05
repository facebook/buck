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

package com.facebook.buck.cxx;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

import org.codehaus.jparsec.Parser;
import org.codehaus.jparsec.Parsers;
import org.codehaus.jparsec.Scanners;
import org.codehaus.jparsec.functors.Map;
import org.codehaus.jparsec.functors.Map2;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class Makefiles {

  private Makefiles() {}

  private static final Parser<Void> WHITESPACE =
      Parsers.or(Scanners.among("\\\n"), Scanners.among(" "), Scanners.among("\t"));

  // Parse the a makefile target. GNU make has an unusual escaping strategy.  Escaped chars need
  // to be escaped with backslashes, and backslashes are only considered escaped if they come
  // immediately before a escaped char.  So you'd escaped "test:target" as "test\:target",
  // "test\\target" remains "test\\target" (since the backslash chain doesn't precede a escaped
  // char), but "test\\:target" gets three chars escaped as "test\\\\\:target".
  private static final Parser<String> ESCAPED_CHARS = Scanners.among(": #").source();
  private static final Parser<String> ESCAPE_CHAR = Scanners.among("\\").source();
  private static final Parser<String> ESCAPED_CHAR =
      ESCAPE_CHAR
          .followedBy(ESCAPE_CHAR.many().next(ESCAPED_CHARS).peek())
          .next(ESCAPE_CHAR.or(ESCAPED_CHARS));
  private static final Parser<String> NORMAL_CHAR = Scanners.notAmong(": #\n").source();
  private static final Parser<String> TARGET_CHAR = ESCAPED_CHAR.or(NORMAL_CHAR);
  private static final Parser<String> TARGET =
      TARGET_CHAR.many1().map(
          new Map<List<String>, String>() {
            @Override
            public String map(List<String> strings) {
              return Joiner.on("").join(strings);
            }
          });

  // Parser for a makefile rule of the form:
  // <target>: <prereq> ...
  private static final Parser<Rule> RULE =
      Parsers.sequence(
          TARGET
              .followedBy(WHITESPACE.many())
              .followedBy(Scanners.among(":"))
              .followedBy(WHITESPACE.many()),
          TARGET
              .sepBy(WHITESPACE.many1())
              .followedBy(Scanners.among("\n")),
          new Map2<String, List<String>, Rule>() {
            @Override
            public Rule map(String target, List<String> prereqs) {
              return new Rule(target, ImmutableList.copyOf(prereqs));
            }
          });

  // Parser for an entire makefile.
  private static final Parser<Makefile> MAKEFILE =
      RULE.endBy(Scanners.among("\n").many()).map(
          new Map<List<Rule>, Makefile>() {
            @Override
            public Makefile map(List<Rule> rules) {
              return new Makefile(ImmutableList.copyOf(rules));
            }
          });

  public static Makefile parseMakefile(Readable readable) throws IOException {
    return MAKEFILE.parse(readable);
  }

  public static class Rule {

    private final String target;
    private final ImmutableList<String> prereqs;

    public Rule(String target, ImmutableList<String> prereqs) {
      this.target = target;
      this.prereqs = prereqs;
    }

    public String getTarget() {
      return target;
    }

    public ImmutableList<String> getPrereqs() {
      return prereqs;
    }

    @Override
    public boolean equals(Object o) {
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Rule rule = (Rule) o;
      return Objects.equals(target, rule.target) && Objects.equals(prereqs, rule.prereqs);
    }

    @Override
    public int hashCode() {
      return Objects.hash(target, prereqs);
    }
  }

  public static class Makefile {

    private final ImmutableList<Rule> rules;

    public Makefile(ImmutableList<Rule> rules) {
      this.rules = rules;
    }

    public ImmutableList<Rule> getRules() {
      return rules;
    }

    @Override
    public boolean equals(Object o) {
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Makefile makefile = (Makefile) o;
      return Objects.equals(rules, makefile.rules);
    }

    @Override
    public int hashCode() {
      return Objects.hash(rules);
    }

  }

}
