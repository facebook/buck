/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ItemComparator.java,v 1.1.1.1 2004/05/09 16:57:37 vlad_r Exp $
 */
package com.vladium.emma.report;

import java.util.Comparator;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
interface ItemComparator extends Comparator
{
    // public: ................................................................
    
    
    ItemComparator NULL_COMPARATOR = new Factory.NullComparator ();
    
    
    abstract class Factory
    {
        public static ItemComparator create (final int [] attributeIDsWithDir, final int unitsID)
        {
            if (attributeIDsWithDir == null)
                throw new IllegalArgumentException ("null input: attributeIDsWithDir");
            
            if (attributeIDsWithDir.length == 0)
                return NULL_COMPARATOR;
                
            // TODO: validate against duplicates
            // TODO: memoize
            
            // TODO: move the code below into the attr factory
            final Comparator [] comparators = new Comparator [attributeIDsWithDir.length >> 1];
            for (int a = 0; a < attributeIDsWithDir.length; a += 2)
            {
                final int attributeID = attributeIDsWithDir [a];
                
                final Comparator comparator = IItemAttribute.Factory.getAttribute (attributeID, unitsID).comparator ();
                comparators [a >> 1] = attributeIDsWithDir [a + 1] < 0 ? new ReverseComparator (comparator) : comparator;
            }
            
            return new CompositeComparator (comparators);
        }

        private static final class NullComparator implements ItemComparator
        {
            public int compare (final Object l, final Object g)
            {
                return 0; 
            }
            
        } // end of nested class
        
        
        private static final class ReverseComparator implements ItemComparator
        {
            public int compare (final Object l, final Object g)
            {
                return m_comparator.compare (g, l); 
            }

            
            ReverseComparator (final Comparator comparator)
            {
                m_comparator = comparator;
            }
            
            
            private final Comparator m_comparator;
            
        } // end of nested class
        
        
        private static final class CompositeComparator implements ItemComparator
        {
            public int compare (final Object l, final Object g)
            {
                // TODO: this needs to check whether both items have a given attr type
                
                for (int c = 0; c < m_comparators.length; ++ c)
                {
                    final int diff = m_comparators [c].compare (l, g);
                    if (diff != 0) return diff;
                }
                
                return 0;
            }

            
            CompositeComparator (final Comparator [] comparators)
            {
                m_comparators = comparators;
            }
            
            
            private final Comparator [] m_comparators;
            
        } // end of nested class
        
    } // end of nested interface 
    
} // end of interface
// ----------------------------------------------------------------------------