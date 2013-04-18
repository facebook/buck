/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ConstantValueAttribute_info.java,v 1.1.1.1 2004/05/09 16:57:47 vlad_r Exp $
 */
package com.vladium.jcd.cls.attribute;

import java.io.IOException;

import com.vladium.jcd.cls.ClassDef;
import com.vladium.jcd.cls.constant.CONSTANT_literal_info;
import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * The ConstantValue attribute is a fixed-length attribute used in the attributes
 * table of the {@link com.vladium.jcd.cls.Field_info} structures. A ConstantValue
 * attribute represents the value of a constant field that must be (explicitly or
 * implicitly) static; that is, the ACC_STATIC bit in the flags item of the
 * Field_info structure must be set. The field is not required to be final. There
 * can be no more than one ConstantValue attribute in the attributes table of a
 * given Field_info structure. The constant field represented by the Field_info
 * structure is assigned the value referenced by its ConstantValue attribute as
 * part of its initialization.<P>
 * 
 * The ConstantValue attribute has the format
 * <PRE>
 *  ConstantValue_attribute {
 *          u2 attribute_name_index;
 *          u4 attribute_length;
 *          u2 constantvalue_index;
 *  }
 * </PRE>
 * 
 * The value of the constantvalue_index item must be a valid index into the constant
 * pool table. The constant pool entry at that index must give the constant value
 * represented by this attribute.<P>
 * 
 * The constant pool entry must be of a type appropriate to the field, as shown below:
 * 
 * <TABLE>
 * <TR><TH>Field Type</TH>                      <TH>Entry Type</TH></TR>
 * <TR><TD>long</TD>                            <TD>CONSTANT_Long</TD></TR>
 * <TR><TD>float</TD>                           <TD>CONSTANT_Float</TD></TR>
 * <TR><TD>double</TD>                          <TD>CONSTANT_Double</TD></TR>
 * <TR><TD>int, short, char, byte, boolean</TD> <TD>CONSTANT_Integer</TD></TR>
 * <TR><TD>java.lang.String</TD>                <TD>CONSTANT_String</TD></TR>
 * </TABLE>
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
final class ConstantValueAttribute_info extends Attribute_info
{
    // public: ................................................................

    
    public int m_value_index;
    
    
    public ConstantValueAttribute_info (final int attribute_name_index, final int value_index)
    {
        super (attribute_name_index, 2);
        
        m_value_index = value_index;
    }
    
    public CONSTANT_literal_info getValue (final ClassDef cls)
    {
        return (CONSTANT_literal_info) cls.getConstants ().get (m_value_index);
    }
    
    public long length ()
    {
        return 8;
    }
    
    // Visitor:
    
    public void accept (final IAttributeVisitor visitor, final Object ctx)
    {
        visitor.visit (this, ctx);
    }
    
    public String toString ()
    {
        // TODO: return more data here
        return "ConstantValueAttribute_info: [attribute_name_index = " + m_name_index + ", attribute_length = " + m_attribute_length + ']';
    
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
        
        out.writeU2 (m_value_index);
    }
    
    // protected: .............................................................
    
    // package: ...............................................................


    ConstantValueAttribute_info (final int attribute_name_index, final long attribute_length,
                                 final UDataInputStream bytes)
        throws IOException
    {
        super (attribute_name_index, attribute_length);
        
        m_value_index = bytes.readU2 ();
        if (DEBUG) System.out.println ("\tconstantvalue_index: " + m_value_index);
    }

    // private: ...............................................................

    
    private static final boolean DEBUG = false;

} // end of class
// ----------------------------------------------------------------------------
