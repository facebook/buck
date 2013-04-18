/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: Field_info.java,v 1.1.1.1 2004/05/09 16:57:45 vlad_r Exp $
 */
package com.vladium.jcd.cls;

import java.io.IOException;

import com.vladium.jcd.cls.attribute.*;
import com.vladium.jcd.cls.constant.CONSTANT_Utf8_info;
import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * Each class field is described by a variable-length field_info structure. The
 * format of this structure is
 * <PRE>
 *  field_info {
 *          u2 access_flags;
 *          u2 name_index;
 *          u2 descriptor_index;
 *          u2 attributes_count;
 *          attribute_info attributes[attributes_count];
 *  }
 * </PRE>
 * 
 * The value of the access_flags item is a mask of modifiers used to describe
 * access permission to and properties of a field.<P>
 * 
 * The value of the name_index item must be a valid index into the constant pool
 * table. The constant pool entry at that index must be a {@link CONSTANT_Utf8_info}
 * structure which must represent a valid Java field name stored as a simple (not
 * fully qualified) name, that is, as a Java identifier.<P>
 * 
 * The value of the descriptor_index item must be a valid index into the constant
 * pool table. The constant pool entry at that index must be a
 * {@link CONSTANT_Utf8_info} structure which must represent a valid Java field
 * descriptor.<P>
 * 
 * Each value of the attributes table must be a variable-length attribute structure.
 * A field can have any number of attributes associated with it. The only attribute
 * defined for the attributes table of a field_info structure at the moment
 * is the ConstantValue attribute -- see {@link ConstantValueAttribute_info}.
 *  
 * @author (C) 2001, Vlad Roubtsov
 */
public
final class Field_info implements Cloneable, IAccessFlags
{
    // public: ................................................................

    
    public int m_name_index;
    public int m_descriptor_index;
    
    
    public Field_info (final int access_flags,
                       final int name_index, final int descriptor_index,
                       final IAttributeCollection attributes)
    {
        m_access_flags = access_flags;
        
        m_name_index = name_index;
        m_descriptor_index = descriptor_index;
        
        m_attributes = attributes;
    }

    public Field_info (final IConstantCollection constants,
                       final UDataInputStream bytes)
        throws IOException
    {
        m_access_flags = bytes.readU2 ();
        
        m_name_index = bytes.readU2 ();
        m_descriptor_index = bytes.readU2 ();
        
        // TODO: put this logic into AttributeCollection
        final int attributes_count = bytes.readU2 ();
        m_attributes = ElementFactory.newAttributeCollection (attributes_count);
        
        for (int i = 0; i < attributes_count; i++)
        {
            final Attribute_info attribute_info = Attribute_info.new_Attribute_info (constants, bytes);
            if (DEBUG) System.out.println ("\t[" + i + "] attribute: " + attribute_info);
            
            m_attributes.add (attribute_info);
        }
    }
    
    /**
     * Returns the field name within the context of 'cls' class definition.
     * 
     * @param cls class that contains this field
     * @return field name
     */
    public String getName (final ClassDef cls)
    {
        return ((CONSTANT_Utf8_info) cls.getConstants ().get (m_name_index)).m_value;
    }
    
    /**
     * Returns the descriptor string for this field within the context of 'cls'
     * class definition.
     * 
     * @param cls class that contains this field
     * @return field typename descriptor
     */
    public String getDescriptor (final ClassDef cls)
    {
        return ((CONSTANT_Utf8_info) cls.getConstants ().get (m_descriptor_index)).m_value;
    }
    
    public boolean isSynthetic ()
    {
        return m_attributes.hasSynthetic ();
    }
    
    // IAccessFlags:
        
    public final void setAccessFlags (final int flags)
    {
        m_access_flags = flags;
    }

    public final int getAccessFlags ()
    {
        return m_access_flags;
    }
    
    public IAttributeCollection getAttributes ()
    {
        return m_attributes;
    }
    
    
    public String toString ()
    {
        return "field_info: [modifiers: 0x" + Integer.toHexString(m_access_flags) + ", name_index = " + m_name_index + ", descriptor_index = " + m_descriptor_index + ']';
    }
    
    
    // Cloneable:
    
    /**
     * Performs a deep copy.
     */
    public Object clone ()
    {
        try
        {
            final Field_info _clone = (Field_info) super.clone ();
            
            // do deep copy:
            _clone.m_attributes = (IAttributeCollection) m_attributes.clone ();
            
            return _clone;
        }
        catch (CloneNotSupportedException e)
        {
            throw new InternalError (e.toString ());
        }
    }
    
    
    // IClassFormatOutput:
    
    public void writeInClassFormat (final UDataOutputStream out) throws IOException
    {    
        out.writeU2 (m_access_flags);
        
        out.writeU2 (m_name_index);
        out.writeU2 (m_descriptor_index);
        
        m_attributes.writeInClassFormat (out);
    }
    
    // protected: .............................................................

    // package: ...............................................................

    // private: ...............................................................

    
    private int m_access_flags;
    private IAttributeCollection m_attributes; // never null

    
    private static final boolean DEBUG = false;
    
} // end of class
// ----------------------------------------------------------------------------
