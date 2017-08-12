// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

// Helper for collecting timing information during execution.
// Timing t = new Timing("R8");
// A timing tree is collected by calling the following pair (nesting will create the tree):
//     t.begin("My task);
//     try { ... } finally { t.end(); }
// or alternatively:
//     t.scope("My task", () -> { ... });
// Finally a report is printed by:
//     t.report();

import java.util.Stack;

public class Timing {

  private final Stack<Node> stack;

  public Timing(String title) {
    stack = new Stack<>();
    stack.push(new Node("Recorded timings for " + title));
  }

  static class Node {
    final String title;

    final Stack<Node> sons = new Stack<>();
    final long start_time;
    long stop_time;

    Node(String title) {
      this.title = title;
      this.start_time = System.nanoTime();
      this.stop_time = -1;
    }

    void end() {
      stop_time = System.nanoTime();
      assert duration() >= 0;
    }

    long duration() {
      return stop_time - start_time;
    }

    @Override
    public String toString() {
      return title + ": " + (duration() / 1000000) + "ms.";
    }

    public String toString(Node top) {
      if (this == top) return toString();
      long percentage = duration() * 100 / top.duration();
      return toString() + " (" + percentage + "%)";
    }

    public void report(int depth, Node top) {
      assert duration() >= 0;
      if (depth > 0) {
        for (int i = 0; i < depth; i++) {
          System.out.print("  ");
        }
        System.out.print("- ");
      }
      System.out.println(toString(top));
      sons.forEach(p -> { p.report(depth + 1, top); });
    }
  }


  public void begin(String title) {
    Node n = new Node(title);
    stack.peek().sons.add(n);
    stack.push(n);
  }

  public void end() {
    stack.peek().end();  // record time.
    stack.pop();
  }

  public void report() {
    Node top = stack.peek();
    top.end();
    System.out.println();
    top.report(0, top);
  }

  public void scope(String title, TimingScope fn) {
    begin(title);
    try {
      fn.apply();
    } finally {
      end();
    }
  }

  public interface TimingScope {
    void apply();
  }
}
