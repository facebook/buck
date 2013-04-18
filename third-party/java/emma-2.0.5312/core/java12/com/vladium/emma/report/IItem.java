/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IItem.java,v 1.1.1.1.2.1 2005/06/12 22:43:11 vlad_r Exp $
 */
package com.vladium.emma.report;

import java.util.Iterator;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
interface IItem
{
    // public: ................................................................
    
    // TODO: consider making this an abstact class [merge into Item]
    
    // note: this design does not enforce all items at the same level being of the same 'type' (class, pkg, method, etc)    

    IItem getParent ();
    int getChildCount ();
    Iterator /* IItem */ getChildren ();
    /**
     * 
     * @param order [null is equivalent to no sort]
     * @return
     */
    Iterator /* IItem */ getChildren (ItemComparator /* IItem */ order);
    
    String getName ();   
    IItemMetadata getMetadata ();
    IItemAttribute getAttribute (int attributeID, int unitsID);
    int getAggregate (int type);
    
    void accept (IItemVisitor visitor, Object ctx);


    // TODO: move these elsewhere and fix gaps
        // WARNING: careful about reordering!
    
        // (coverage data) measured in counts:
        int COVERAGE_CLASS_COUNT    = 5; // count of class loads
        int COVERAGE_METHOD_COUNT   = 4; // count of method entries
        
        // (coverage data) measured in counts or instrs:
        int COVERAGE_BLOCK_COUNT    = 0; // in count units
        int COVERAGE_LINE_COUNT     = 1; // in count units
        int COVERAGE_BLOCK_INSTR    = 2; // in instr units
        int COVERAGE_LINE_INSTR     = 3; // total line instr coverage, scaled up by PRECISION
        
    
        // (metadata) measured in counts:
        int TOTAL_CLASS_COUNT       = 11;
        int TOTAL_METHOD_COUNT      = 10;
    
        // (metadata) measured in counts or instrs:
        int TOTAL_BLOCK_COUNT       = 6; // in count units
        int TOTAL_LINE_COUNT        = 7; // in count units
        int TOTAL_BLOCK_INSTR       = 8; // in instr units
        //int TOTAL_LINE_INSTR        = 9; // in instr units
       
        int TOTAL_SRCFILE_COUNT     = 12;
        //int TOTAL_SRCLINE_COUNT     = 13;
        
        int NUM_OF_AGGREGATES = TOTAL_SRCFILE_COUNT + 1;
        int PRECISION = 100; // BUG_SF988160: increase overflow safety margin for very large projects  
   
} // end of interface
// ----------------------------------------------------------------------------