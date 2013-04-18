/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: SyntheticAttribute_info.java,v 1.1.1.1.2.1 2004/07/10 03:34:52 vlad_r Exp $
 */
package com.vladium.jcd.cls.attribute;

import java.io.IOException;

import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * The Synthetic attribute is a fixed-length attribute in the attributes table
 * of ClassFile, {@link com.vladium.jcd.cls.Field_info}, and
 * {@link com.vladium.jcd.cls.Method_info} structures. A class member that does
 * not appear in the source code must be marked using a Synthetic attribute.<P>
 * 
 * The Synthetic attribute has the following format:
 * <PRE>
 * Synthetic_attribute {
 *          u2 attribute_name_index;
 *          u4 attribute_length;
 * }
 * </PRE>
 * 
 * The value of the attribute_name_index item must be a valid index into the
 * constant_pool table. The constant_pool entry at that index must be a CONSTANT_Utf8_info
 * structure representing the string "Synthetic".<P>
 * 
 * The value of the attribute_length item is zero.
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
final class SyntheticAttribute_info extends Attribute_info
{
    // public: ................................................................
    
    
    public SyntheticAttribute_info (final int attribute_name_index)
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
        return "SyntheticValueAttribute_info: [attribute_name_index = " + m_name_index + ", attribute_length = " + m_attribute_length + ']';
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


    SyntheticAttribute_info (final int attribute_name_index, final long attribute_length)
    {
        super (attribute_name_index, attribute_length);
    }
    
    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------

