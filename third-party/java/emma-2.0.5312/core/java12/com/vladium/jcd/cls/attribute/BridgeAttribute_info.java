/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: BridgeAttribute_info.java,v 1.1.1.1.2.1 2004/07/10 03:34:52 vlad_r Exp $
 */
package com.vladium.jcd.cls.attribute;

import java.io.IOException;

import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * New attribute added by J2SE 1.5
 * 
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class BridgeAttribute_info extends Attribute_info
{
    // public: ................................................................
    
    
    public BridgeAttribute_info (final int attribute_name_index)
    {
        super (attribute_name_index, 0);
    }
    
    
    public long length ()
    {
        return 6;
    }
    
    // Visitor:
    
    public void accept (final IAttributeVisitor visitor, final Object ctx)
    {
        visitor.visit (this, ctx);
    }
    
    public String toString ()
    {
        return "BridgeAttribute_info: [attribute_name_index = " + m_name_index + ", attribute_length = " + m_attribute_length + ']';
    }
    
    // Cloneable:
    
    /**
     * Performs a deep copy.
     */
    public Object clone ()
    {        
        return super.clone ();    
    }
       
    // IClassFormatOutput:
    
    public void writeInClassFormat (final UDataOutputStream out) throws IOException
    {
        super.writeInClassFormat (out);
    }
    
    // protected: .............................................................
    
    // package: ...............................................................


    BridgeAttribute_info (final int attribute_name_index, final long attribute_length)
    {
        super (attribute_name_index, attribute_length);
    }
    
    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------

