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

package com.facebook.buck.java.tracing;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.model.BuildTarget;
import com.google.common.collect.ImmutableMap;

/**
 * Base class for events about the phases of compilation within javac.
 */
public abstract class JavacPhaseEvent extends AbstractBuckEvent {
  public enum Phase {
    /**
     * Parsing a single source file. Filename will be in the args.
     */
    PARSE("parse"),
    /**
     * Entering all parse trees into the compiler's symbol tables. All filenames will be in the
     * args.
     */
    ENTER("enter"),
    /**
     * Overall annotation processing phase, including all rounds. No args.
     */
    ANNOTATION_PROCESSING("annotation processing"),
    /**
     * A single round of annotation processing, including running the relevant processors, parsing
     * any source files they create, then re-entering all previously parsed files into new symbol
     * tables. Round number and whether it's the last round will be in the args.
     */
    ANNOTATION_PROCESSING_ROUND("annotation processing round"),
    /**
     * Just running the annotation processors that are relevant to the sources being compiled.
     * No args.
     */
    RUN_ANNOTATION_PROCESSORS("run annotation processors"),
    /**
     * Analyze and transform the AST for a single type to prepare for code generation. Source file
     * and type being analyzed will be in the args.
     */
    ANALYZE("analyze"),
    /**
     * Generate a class file for a single type. Source file and type being generated will be in the
     * args.
     */
    GENERATE("generate");

    private final String name;

    Phase(String name) {
      this.name = name;
    }


    @Override
    public String toString() {
      return name;
    }
  }

  private final BuildTarget buildTarget;
  private final Phase phase;
  private final ImmutableMap<String, String> args;

  protected JavacPhaseEvent(
      BuildTarget buildTarget,
      Phase phase,
      ImmutableMap<String, String> args) {
    this.buildTarget = buildTarget;
    this.phase = phase;
    this.args = args;
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public Phase getPhase() {
    return phase;
  }

  public ImmutableMap<String, String> getArgs() {
    return args;
  }

  @Override
  protected String getValueString() {
    return buildTarget.toString();
  }

  public static Started started(
      BuildTarget buildTarget,
      Phase phase,
      ImmutableMap<String, String> args) {
    return new Started(buildTarget, phase, args);
  }

  public static Finished finished(
      Started startedEvent,
      ImmutableMap<String, String> args) {
    return new Finished(startedEvent, args);
  }

  public static class Started extends JavacPhaseEvent {
    public Started(
        BuildTarget buildTarget,
        Phase phase,
        ImmutableMap<String, String> args) {
      super(buildTarget, phase, args);
    }

    @Override
    public String getEventName() {
      return String.format("javac.%sStarted", getPhase().toString());
    }
  }

  public static class Finished extends JavacPhaseEvent {
    public Finished(
        Started startedEvent,
        ImmutableMap<String, String> args) {
      super(startedEvent.getBuildTarget(), startedEvent.getPhase(), args);
      chain(startedEvent);
    }

    @Override
    public String getEventName() {
      return String.format("javac.%sFinished", getPhase().toString());
    }
  }
}
