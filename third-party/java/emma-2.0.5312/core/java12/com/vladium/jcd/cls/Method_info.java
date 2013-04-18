/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: Method_info.java,v 1.1.1.1 2004/05/09 16:57:47 vlad_r Exp $
 */
package com.vladium.jcd.cls;

import java.io.IOException;

import com.vladium.jcd.cls.attribute.*;
import com.vladium.jcd.cls.constant.CONSTANT_Utf8_info;
import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * Each class method, and each instance initialization method <init>, is described
 * by a variable-length method_info structure. The structure has the following
 * format:
 * <PRE>
 *  method_info {
 *          u2 access_flags;
 *          u2 name_index;
 *          u2 descriptor_index;
 *          u2 attributes_count;
 *          attribute_info attributes[attributes_count];
 *  }
 * </PRE>
 * 
 * The value of the access_flags item is a mask of modifiers used to describe
 * access permission to and properties of a method or instance initialization method.<P>
 * 
 * The value of the name_index item must be a valid index into the constant pool
 * table. The constant pool entry at that index must be a {@link CONSTANT_Utf8_info}
 * structure representing either one of the special internal method names, either
 * &lt;init&gt; or &lt;clinit&gt;, or a valid Java method name, stored as a simple
 * (not fully qualified) name.<P>
 * 
 * The value of the descriptor_index item must be a valid index into the constant pool
 * table. The constant pool entry at that index must be a {@link CONSTANT_Utf8_info}
 * structure representing a valid Java method descriptor.<P>
 * 
 * Each value of the attributes table must be a variable-length attribute structure.
 * A method can have any number of optional attributes associated with it. The only
 * attributes defined by this specification for the attributes table of a method_info
 * structure are the Code and Exceptions attributes. See {@link CodeAttribute_info}
 * and {@link ExceptionsAttribute_info}.
 *  
 * @author (C) 2001, Vlad Roubtsov
 */
public
final class Method_info implements Cloneable, IAccessFlags
{
    // public: ................................................................

    
    public int m_name_index;
    public int m_descriptor_index;
    
    
    public Method_info (int access_flags, int name_index, int descriptor_index, IAttributeCollection attributes)
    {
        m_access_flags = access_flags;
        
        m_name_index = name_index;
        m_descriptor_index = descriptor_index;
        
        m_attributes = attributes;
    }


    public Method_info (final IConstantCollection constants,
                        final UDataInputStream bytes)
        throws IOException
    {
        m_access_flags = bytes.readU2 ();
        
        m_name_index = bytes.readU2 ();
        m_descriptor_index = bytes.readU2 ();
        
        // TODO: put this logic into AttributeCollection
        
        final int attributes_count = bytes.readU2 ();        
        m_attributes = ElementFactory.newAttributeCollection (attributes_count);
        
        for (int i = 0; i < attributes_count; ++ i)
        {
            final Attribute_info attribute_info = Attribute_info.new_Attribute_info (constants, bytes);
            
            m_attributes.add (attribute_info);
        }
    }
   
    /**
     * Returns the method name within the context of 'cls' class definition.
     * 
     * @param cls class that contains this method
     * @return method name
     */
    public String getName (final ClassDef cls)
    {
        return ((CONSTANT_Utf8_info) cls.getConstants ().get (m_name_index)).m_value;
    }
    
    /**
     * Returns the descriptor string for this method within the context of 'cls'
     * class definition.
     * 
     * @param cls class that contains this method
     * @return field typename descriptor
     */
    public String getDescriptor (final ClassDef cls)
    {
        return ((CONSTANT_Utf8_info) cls.getConstants ().get (m_descriptor_index)).m_value;
    }
    
    public boolean isNative ()
    {
        return (m_access_flags & ACC_NATIVE) != 0;
    }
    
    public boolean isAbstract ()
    {
        return (m_access_flags & ACC_ABSTRACT) != 0;
    }
    
    public boolean isSynthetic ()
    {
        return m_attributes.hasSynthetic ();
    }
    
    public boolean isBridge ()
    {
        return ((m_access_flags & ACC_BRIDGE) != 0) || m_attributes.hasBridge ();
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
        StringBuffer s = new StringBuffer ();
        
        s.append ("method_info: [modifiers: 0x" + Integer.toHexString(m_access_flags) + ", name_index = " + m_name_index + ", descriptor_index = " + m_descriptor_index + "]\n");
        for (int i = 0; i < m_attributes.size (); i++)
        {
            Attribute_info attribute_info = m_attributes.get (i);
            
            s.append ("\t[" + i + "] attribute: " + attribute_info + "\n");
        }

        return s.toString ();
    }
    
    
    // Cloneable:
    
    /**
     * Performs a deep copy.
     */
    public Object clone ()
    {
        try
        {
            final Method_info _clone = (Method_info) super.clone ();
            
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
    private IAttributeCollection m_attributes;

} // end of class
// ----------------------------------------------------------------------------
