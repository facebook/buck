/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: PackageItem.java,v 1.1.1.1 2004/05/09 16:57:38 vlad_r Exp $
 */
package com.vladium.emma.report;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class PackageItem extends Item
{
    // public: ................................................................
    
    public PackageItem (final IItem parent, final String name, final String VMname) // TODO: this is VM name for now
    {
        super (parent);
        
        m_name = name;
        m_VMname = VMname;
    }
    
    public String getName ()
    {
        return m_name;
    }
    
    public String getVMName ()
    {
        return m_VMname;
    }
    
    public void accept (final IItemVisitor visitor, final Object ctx)
    {
        visitor.visit (this, ctx);
    }
    
    public final IItemMetadata getMetadata ()
    {
        return METADATA;
    }
    
    public static IItemMetadata getTypeMetadata ()
    {
        return METADATA;
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private final String m_name, m_VMname;
    
    private static final Item.ItemMetadata METADATA; // set in <clinit>
        
    static
    {
        METADATA = new Item.ItemMetadata (IItemMetadata.TYPE_ID_PACKAGE, "package",
            1 << IItemAttribute.ATTRIBUTE_NAME_ID |
            1 << IItemAttribute.ATTRIBUTE_CLASS_COVERAGE_ID |
            1 << IItemAttribute.ATTRIBUTE_METHOD_COVERAGE_ID |
            1 << IItemAttribute.ATTRIBUTE_BLOCK_COVERAGE_ID |
            1 << IItemAttribute.ATTRIBUTE_LINE_COVERAGE_ID);
    }


} // end of class
// ----------------------------------------------------------------------------