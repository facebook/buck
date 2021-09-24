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

package com.facebook.buck.event;

import com.facebook.buck.log.views.JsonViews;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public abstract class WatchmanStatusEvent extends AbstractBuckEvent implements BuckEvent {
  private final String eventName;

  public WatchmanStatusEvent(EventKey eventKey, String eventName) {
    super(eventKey);
    this.eventName = eventName;
  }

  @Override
  protected String getValueString() {
    return eventName;
  }

  @Override
  public String getEventName() {
    return eventName;
  }

  public static Started started() {
    return new Started();
  }

  public static Finished finished() {
    return new Finished();
  }

  public static Overflow overflow(String reason, Path cellPath) {
    return new Overflow(reason, cellPath);
  }

  public static FileCreation fileCreation(String filename) {
    return new FileCreation(filename);
  }

  public static FileDeletion fileDeletion(String filename) {
    return new FileDeletion(filename);
  }

  public static FileChangesSinceLastBuild fileChangesSinceLastBuild(
      List<FileChangesSinceLastBuild.FileChange> fileChanges, boolean tooManyFileChanges) {
    return new FileChangesSinceLastBuild(fileChanges, tooManyFileChanges);
  }

  public static ZeroFileChanges zeroFileChanges() {
    return new ZeroFileChanges();
  }

  public static class Started extends WatchmanStatusEvent {
    public Started() {
      super(EventKey.unique(), "WatchmanStarted");
    }
  }

  public static class Finished extends WatchmanStatusEvent {
    public Finished() {
      super(EventKey.unique(), "WatchmanFinished");
    }
  }

  public static class Overflow extends WatchmanStatusEvent {
    @JsonView(JsonViews.MachineReadableLog.class)
    private String reason;

    private Path cellPath;

    public Overflow(String reason, Path cellPath) {
      super(EventKey.unique(), "WatchmanOverflow");
      this.reason = reason;
      this.cellPath = cellPath;
    }

    public String getReason() {
      return reason;
    }

    /** @return Absolute path of the cell where watchman overflow happened */
    public Path getCellPath() {
      return cellPath;
    }
  }

  public static class FileCreation extends WatchmanStatusEvent {
    @JsonView(JsonViews.MachineReadableLog.class)
    private String filename;

    public FileCreation(String filename) {
      super(EventKey.unique(), "WatchmanFileCreation");
      this.filename = filename;
    }

    public String getFilename() {
      return this.filename;
    }
  }

  public static class FileDeletion extends WatchmanStatusEvent {
    @JsonView(JsonViews.MachineReadableLog.class)
    private String filename;

    public FileDeletion(String filename) {
      super(EventKey.unique(), "WatchmanFileDeletion");
      this.filename = filename;
    }

    public String getFilename() {
      return this.filename;
    }
  }

  /** FileChangesSinceLastBuild is used for represent file changes since last buck build */
  public static class FileChangesSinceLastBuild extends WatchmanStatusEvent {

    /** FileChange is used for represent a file change since last buck build */
    public static class FileChange {
      private final String cellPath;
      private final String filePath;
      private final String kind;

      public FileChange(String cellPath, String filePath, String kind) {
        this.cellPath = cellPath;
        this.filePath = filePath;
        this.kind = kind;
      }

      public String getFilePath() {
        return "filePath=" + this.filePath;
      }

      public String getCellPath() {
        return "cellPath=" + this.cellPath;
      }

      @Override
      public String toString() {
        return kind + "{" + getCellPath() + ", " + getFilePath() + "}";
      }
    }

    private final List<FileChange> fileChangeList;
    private final boolean tooManyFileChanges;

    public FileChangesSinceLastBuild(List<FileChange> fileChangeList, boolean tooManyFileChanges) {
      super(EventKey.unique(), "FileChangesSinceLastBuild");
      this.fileChangeList = fileChangeList;
      this.tooManyFileChanges = tooManyFileChanges;
    }

    public List<FileChange> getFileChangeList() {
      return fileChangeList;
    }

    /** getEventPrintable is used for represent a series of file changes info */
    public List<String> getEventPrintable() {
      // This list can be very long
      if (fileChangeList.size() == 0) {
        return tooManyFileChanges
            ? ImmutableList.of("Too many file changes (>10000)")
            : ImmutableList.of("0 file change");
      }
      return fileChangeList.stream().map(FileChange::toString).collect(Collectors.toList());
    }
  }

  /** This event is to be posted when Watchman does not report any altered files. */
  public static class ZeroFileChanges extends WatchmanStatusEvent {
    public ZeroFileChanges() {
      super(EventKey.unique(), "WatchmanZeroFileChanges");
    }
  }
}
