// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class DominatorTree {
  private BasicBlock[] sorted;
  private BasicBlock[] doms;
  private BasicBlock normalExitBlock = new BasicBlock();

  public DominatorTree(IRCode code) {
    this(code, Collections.emptyList());
  }

  // TODO(sgjesse) Get rid of this constructor and blocksToIgnore.
  DominatorTree(IRCode code, List<BasicBlock> blocksToIgnore) {
    BasicBlock[] blocks = code.topologicallySortedBlocks(blocksToIgnore);
    // Add the internal exit block to the block list.
    for (BasicBlock block : blocks) {
      if (block.exit().isReturn()) {
        normalExitBlock.getPredecessors().add(block);
      }
    }
    sorted = new BasicBlock[blocks.length + 1];
    System.arraycopy(blocks, 0, sorted, 0, blocks.length);
    sorted[blocks.length] = normalExitBlock;
    numberBlocks();
    build();
  }

  /**
   * Get the immediate dominator block for a block.
   */
  public BasicBlock immediateDominator(BasicBlock block) {
    return doms[block.getNumber()];
  }

  /**
   * Check if one basic block is dominated by another basic block.
   *
   * @param subject subject to check for domination by {@code dominator}
   * @param dominator dominator to check against
   * @return wether {@code subject} is dominated by {@code dominator}
   */
  public boolean dominatedBy(BasicBlock subject, BasicBlock dominator) {
    if (subject == dominator) {
      return true;
    }
    return strictlyDominatedBy(subject, dominator);
  }

  /**
   * Check if one basic block is strictly dominated by another basic block.
   *
   * @param subject subject to check for domination by {@code dominator}
   * @param dominator dominator to check against
   * @return wether {@code subject} is strictly dominated by {@code dominator}
   */
  public boolean strictlyDominatedBy(BasicBlock subject, BasicBlock dominator) {
    if (subject.getNumber() == 0 || subject == normalExitBlock) {
      return false;
    }
    while (true) {
      BasicBlock idom = immediateDominator(subject);
      if (idom.getNumber() < dominator.getNumber()) {
        return false;
      }
      if (idom == dominator) {
        return true;
      }
      subject = idom;
    }
  }

  /**
   * Use the dominator tree to find the dominating block that is closest to a set of blocks.
   *
   * @param blocks the block for which to find a dominator
   * @return the closest dominator for the collection of blocks
   */
  public BasicBlock closestDominator(Collection<BasicBlock> blocks) {
    if (blocks.size() == 0) {
      return null;
    }
    Iterator<BasicBlock> it = blocks.iterator();
    BasicBlock dominator = it.next();
    while (it.hasNext()) {
      dominator = intersect(dominator, it.next());
    }
    return dominator;
  }

  /** Returns an iterator over all blocks dominated by dominator, including dominator itself. */
  public Iterable<BasicBlock> dominatedBlocks(BasicBlock dominator) {
    return () ->
        new Iterator<BasicBlock>() {
          private int current = dominator.getNumber();

          @Override
          public boolean hasNext() {
            return dominatedBy(sorted[current], dominator);
          }

          @Override
          public BasicBlock next() {
            if (!hasNext()) {
              return null;
            } else {
              return sorted[current++];
            }
          }
        };
  }

  /**
   * Returns an iterator over all dominator blocks of <code>dominated</code>.
   *
   * Iteration order is always the immediate dominator of the previously returned block. The
   * iteration starts by returning <code>dominated</code>.
   */
  public Iterable<BasicBlock> dominatorBlocks(BasicBlock dominated) {
    return () -> new Iterator<BasicBlock>() {
      private BasicBlock current = dominated;

      @Override
      public boolean hasNext() {
        return current != null;
      }

      @Override
      public BasicBlock next() {
        if (!hasNext()) {
          return null;
        } else {
          BasicBlock result = current;
          if (current.getNumber() == 0) {
            current = null;
          } else {
            current = immediateDominator(current);
            assert current != result;
          }
          return result;
        }
      }
    };
  }

  public Iterable<BasicBlock> normalExitDominatorBlocks() {
    return dominatorBlocks(normalExitBlock);
  }

  public BasicBlock[] getSortedBlocks() {
    return sorted;
  }

  private void numberBlocks() {
    for (int i = 0; i < sorted.length; i++) {
      sorted[i].setNumber(i);
    }
  }

  private boolean postorderCompareLess(BasicBlock b1, BasicBlock b2) {
    // The topological sort is reverse postorder.
    return b1.getNumber() > b2.getNumber();
  }

  // Build dominator tree based on the algorithm described in this paper:
  //
  // A Simple, Fast Dominance Algorithm
  // Cooper, Keith D.; Harvey, Timothy J.; and Kennedy, Ken (2001).
  // http://www.cs.rice.edu/~keith/EMBED/dom.pdf
  private void build() {
    doms = new BasicBlock[sorted.length];
    doms[0] = sorted[0];
    boolean changed = true;
    while (changed) {
      changed = false;
      // Run through all nodes in reverse postorder (except start node).
      for (int i = 1; i < sorted.length; i++) {
        BasicBlock b = sorted[i];
        // Pick one processed predecessor.
        BasicBlock newIDom = null;
        int picked = -1;
        for (int j = 0; newIDom == null && j < b.getPredecessors().size(); j++) {
          BasicBlock p = b.getPredecessors().get(j);
          if (doms[p.getNumber()] != null) {
            picked = j;
            newIDom = p;
          }
        }
        // Run through all other predecessors.
        for (int j = 0; j < b.getPredecessors().size(); j++) {
          BasicBlock p = b.getPredecessors().get(j);
          if (j == picked) {
            continue;
          }
          if (doms[p.getNumber()] != null) {
            newIDom = intersect(p, newIDom);
          }
        }
        if (doms[b.getNumber()] != newIDom) {
          doms[b.getNumber()] = newIDom;
          changed = true;
        }
      }
    }
  }

  private BasicBlock intersect(BasicBlock b1, BasicBlock b2) {
    BasicBlock finger1 = b1;
    BasicBlock finger2 = b2;
    while (finger1 != finger2) {
      while (postorderCompareLess(finger1, finger2)) {
        finger1 = doms[finger1.getNumber()];
      }
      while (postorderCompareLess(finger2, finger1)) {
        finger2 = doms[finger2.getNumber()];
      }
    }
    return finger1;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("Dominators\n");
    for (BasicBlock block : sorted) {
      builder.append(block.getNumber());
      builder.append(": ");
      builder.append(doms[block.getNumber()].getNumber());
      builder.append("\n");
    }
    return builder.toString();
  }
}
