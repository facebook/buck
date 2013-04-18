/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: Item.java,v 1.1.1.1.2.1 2004/06/20 20:14:39 vlad_r Exp $
 */
package com.vladium.emma.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import com.vladium.util.asserts.$assert;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
abstract class Item implements IItem
{
    // public: ................................................................

    
    // IItem:
    
    public final int getChildCount ()
    {
        return m_children.size ();
    }

    public final IItem getParent ()
    {
        return m_parent;
    }

    public final Iterator getChildren ()
    {
        return m_children.iterator ();
    }
    
    public final Iterator getChildren (final ItemComparator /* IItem */ order)
    {
        // TODO: soft caching keyed off 'order'
        
        if (order == null)
            return getChildren ();
        else
        {        
            final IItem [] items = new IItem [m_children.size ()];
            m_children.toArray (items);
            
            Arrays.sort (items, order);
            
            return Arrays.asList (items).iterator ();
        }
    }
    
    public final IItemAttribute getAttribute (final int attributeID, final int unitsID)
    {
        //if ($assert.ENABLED) $assert.ASSERT ((attributeID & getMetadata ().getAttributeIDs ()) != 0, "invalid attribute ID [" + attributeID + "] for type [" + getMetadata ().getTypeID () + "]");
        
        if ((getMetadata ().getAttributeIDs () & (1 << attributeID)) == 0)
            return null;
        else
            return IItemAttribute.Factory.getAttribute (attributeID, unitsID);
    }
    
    public int getAggregate (final int type)
    {
        final int [] aggregates = m_aggregates;
        int value = aggregates [type];
        
        if (value < 0)
        {
            // don't fault aggregate types all at once since there are
            // plenty of exceptions to the additive roll up rule:
            
            value = 0;
            for (Iterator children = m_children.iterator (); children.hasNext (); )
            {
                value += ((IItem) children.next ()).getAggregate (type);
            }
            aggregates [type] = value;
            
            return value;
        }
        
        return value;
    }

    // protected: .............................................................
    
    
    protected static final class ItemMetadata implements IItemMetadata
    {
        public int getTypeID ()
        {
            return m_typeID;
        }
        
        public String getTypeName ()
        {
            return m_typeName;
        }
        
        public long getAttributeIDs ()
        {
            return m_attributeIDs;
        }
        
        ItemMetadata (final int typeID, final String typeName, final long attributeIDs)
        {
            if ($assert.ENABLED) $assert.ASSERT (typeID >= TYPE_ID_ALL && typeID <= TYPE_ID_METHOD, "invalid type ID: " + typeID);
            if ($assert.ENABLED) $assert.ASSERT (typeName != null, "typeName = null");
            
            
            m_typeID = typeID;
            m_typeName = typeName;
            m_attributeIDs = attributeIDs;
        }


        private final int m_typeID;
        private final String m_typeName;
        private final long m_attributeIDs;
        
    } // end of nested class


    protected void addChild (final IItem item)
    {
        if (item == null) throw new IllegalArgumentException ("null input: item");
        
        m_children.add (item);
    }
    
    
    protected final IItem m_parent;
    protected final int [] m_aggregates;

    // package: ...............................................................
    

    Item (final IItem parent)
    {
        m_parent = parent;
        m_children = new ArrayList ();
        
        m_aggregates = new int [NUM_OF_AGGREGATES];
        for (int i = 0; i < m_aggregates.length; ++ i) m_aggregates [i] = -1;
    }
    
    // private: ...............................................................
    
    
    private final List m_children;

} // end of class
// ----------------------------------------------------------------------------