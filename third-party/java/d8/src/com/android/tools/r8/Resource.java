// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Represents application resources. */
public abstract class Resource {

  /**
   * Origin description of a resource.
   *
   * <p>An origin is a list of parts that describe where a resource originates from. The first part
   * is the most recent part and is associated with the present resource, each successive part is
   * then associated with the context of the previous part.
   *
   * <p>For example, for a class file, say {@code my/class/Foo.class}, that is contained within a
   * jar archive, say {@code myjar.jar}, the Origin of of this resource will be {@code
   * myjar.jar:my/class/Foo.class} where each part is separated by a colon.
   *
   * <p>There are two top-most origins which have no parent: {@code Origin.root()} and {@code
   * Origin.unknown()}. The former is the parent of any file path, while the latter is an unknown
   * origin (e.g., for generated resources of raw bytes).
   */
  public abstract static class Origin implements Comparable<Origin> {

    private static final Origin ROOT =
        new Origin() {
          @Override
          public String part() {
            return "";
          }

          @Override
          List<String> buildParts(int size) {
            return new ArrayList<>(size);
          }
        };

    private static final Origin UNKNOWN =
        new Origin() {
          @Override
          public String part() {
            return "<unknown>";
          }

          @Override
          List<String> buildParts(int size) {
            List<String> parts = new ArrayList<>(size + 1);
            parts.add(part());
            return parts;
          }
        };

    public static Origin root() {
      return ROOT;
    }

    public static Origin unknown() {
      return UNKNOWN;
    }

    private final Origin parent;

    private Origin() {
      this.parent = null;
    }

    protected Origin(Origin parent) {
      assert parent != null;
      this.parent = parent;
    }

    public abstract String part();

    public Origin parent() {
      return parent;
    }

    public List<String> parts() {
      return buildParts(0);
    }

    List<String> buildParts(int size) {
      List<String> parts = parent().buildParts(size + 1);
      parts.add(part());
      return parts;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      }
      if (!(obj instanceof Origin)) {
        return false;
      }
      Origin self = this;
      Origin other = (Origin) obj;
      while (self != null && other != null && self.part().equals(other.part())) {
        self = self.parent();
        other = other.parent();
      }
      return self == other;
    }

    @Override
    public int compareTo(Origin other) {
      // Lexicographic ordering from root to leaf.
      List<String> thisParts = parts();
      List<String> otherParts = other.parts();
      int len = Math.min(thisParts.size(), otherParts.size());
      for (int i = 0; i < len; i++) {
        int compare = thisParts.get(i).compareTo(otherParts.get(i));
        if (compare != 0) {
          return compare;
        }
      }
      return Integer.compare(thisParts.size(), otherParts.size());
    }

    @Override
    public int hashCode() {
      int hash = 1;
      for (String part : parts()) {
        hash = 31 * hash + part.hashCode();
      }
      return hash;
    }

    @Override
    public String toString() {
      return String.join(":", parts());
    }
  }

  /** Path component in an origin description. */
  public static class PathOrigin extends Origin {
    private final Path path;

    public PathOrigin(Path path, Origin parent) {
      super(parent);
      assert path != null;
      this.path = path;
    }

    @Override
    public String part() {
      return path.toString();
    }
  }

  /** Origin of the resource. */
  public final Origin origin;

  protected Resource(Origin origin) {
    this.origin = origin;
  }

  /** Create an application resource for a given file. */
  public static Resource fromFile(Path file) {
    return new FileResource(file);
  }

  /** Create an application resource for a given content. */
  public static Resource fromBytes(Origin origin, byte[] bytes) {
    return fromBytes(origin, bytes, null);
  }

  /** Create an application resource for a given content and type descriptor. */
  public static Resource fromBytes(Origin origin, byte[] bytes, Set<String> typeDescriptors) {
    return new ByteResource(origin, bytes, typeDescriptors);
  }

  /**
   * Returns the set of class descriptors for classes represented
   * by the resource if known, or `null' otherwise.
   */
  public abstract Set<String> getClassDescriptors();

  /** Get the resource as a stream. */
  public abstract InputStream getStream() throws IOException;

  /**
   * File-based application resource.
   *
   * <p>The origin of a file resource is the path of the file.
   */
  private static class FileResource extends Resource {
    final Path file;

    FileResource(Path file) {
      super(new PathOrigin(file, Origin.root()));
      assert file != null;
      this.file = file;
    }

    @Override
    public Set<String> getClassDescriptors() {
      return null;
    }

    @Override
    public InputStream getStream() throws IOException {
      return new FileInputStream(file.toFile());
    }
  }

  /**
   * Byte-content based application resource.
   *
   * <p>The origin of a byte resource must be supplied upon construction. If no reasonable origin
   * exits, use {@code Origin.unknown()}.
   */
  private static class ByteResource extends Resource {
    final Set<String> classDescriptors;
    final byte[] bytes;

    ByteResource(Origin origin, byte[] bytes, Set<String> classDescriptors) {
      super(origin);
      assert bytes != null;
      this.classDescriptors = classDescriptors;
      this.bytes = bytes;
    }

    @Override
    public Set<String> getClassDescriptors() {
      return classDescriptors;
    }

    @Override
    public InputStream getStream() throws IOException {
      return new ByteArrayInputStream(bytes);
    }
  }
}
