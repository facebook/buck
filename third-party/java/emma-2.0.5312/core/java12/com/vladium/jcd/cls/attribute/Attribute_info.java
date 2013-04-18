/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: Attribute_info.java,v 1.1.1.1 2004/05/09 16:57:47 vlad_r Exp $
 */
package com.vladium.jcd.cls.attribute;

import java.io.IOException;

import com.vladium.jcd.cls.ClassDef;
import com.vladium.jcd.cls.IConstantCollection;
import com.vladium.jcd.cls.constant.*;
import com.vladium.jcd.compiler.IClassFormatOutput;
import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * Abstract base for all XXXAttribute_info structures. It also works in conjunction
 * with {@link GenericAttribute_info} class to process all unrecognized attributes.<P>
 * 
 * Attributes are used in the {@link com.vladium.jcd.cls.ClassDef}, {@link com.vladium.jcd.cls.Field_info},
 * {@link com.vladium.jcd.cls.Method_info}, and {@link CodeAttribute_info}
 * structures of the .class file format. All attributes have the following
 * general format:
 * <PRE>
 *  attribute_info {
 *          u2 attribute_name_index;
 *          u4 attribute_length;
 *          u1 info[attribute_length];
 *  }
 * </PRE>
 * 
 * For all attributes, the attribute_name_index must be a valid unsigned 16-bit
 * index into the constant pool of the class. The constant pool entry at
 * attribute_name_index must be a {@link com.vladium.jcd.cls.constant.CONSTANT_Utf8_info}
 * string representing the name of the attribute. The value of the attribute_length
 * item indicates the length of the subsequent information in bytes. The length
 * does not include the initial six bytes that contain the attribute_name_index
 * and attribute_length items.
 * 
 * @see GenericAttribute_info
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
abstract class Attribute_info implements Cloneable, IClassFormatOutput
{
    // public: ................................................................
    
    public static final String ATTRIBUTE_CODE               = "Code";
    public static final String ATTRIBUTE_CONSTANT_VALUE     = "ConstantValue";
    public static final String ATTRIBUTE_LINE_NUMBER_TABLE  = "LineNumberTable";
    public static final String ATTRIBUTE_EXCEPTIONS         = "Exceptions";
    public static final String ATTRIBUTE_SYNTHETIC          = "Synthetic";
    public static final String ATTRIBUTE_BRIDGE             = "Bridge";
    public static final String ATTRIBUTE_SOURCEFILE         = "SourceFile";
    public static final String ATTRIBUTE_INNERCLASSES       = "InnerClasses";

    /**
     * Constant pool index for {@link com.vladium.jcd.cls.constant.CONSTANT_Utf8_info}
     * string representing the name of this attribute [always positive].
     */
    public int m_name_index;
    
    /**
     * Returns the name for this attribute within the constant pool context of 'cls'
     * class definition.
     * 
     * @param cls class that contains this attribute
     * @return attribute name
     */
    public String getName (final ClassDef cls)
    {
        return ((CONSTANT_Utf8_info) cls.getConstants ().get (m_name_index)).m_value;
    }
    
    /**
     * Returns the total length of this attribute when converted to
     * .class format [including the 6-byte header]
     */
    public abstract long length (); // including the 6-byte header
    
    // Visitor:
    
    public abstract void accept (IAttributeVisitor visitor, Object ctx); 
    
    public abstract String toString ();
    
    // TODO: use a hashmap lookup in this method + control which set of attrs get mapped to generic
    /**
     * Parses out a single Attribute_info element out of .class data in
     * 'bytes'.
     * 
     * @param constants constant pool for the parent class [may not be null; not validated]
     * @param bytes input .class data stream [may not be null; not validated]
     *  
     * @return a single parsed attribute
     * 
     * @throws IOException on input errors
     */
    public static Attribute_info new_Attribute_info (final IConstantCollection constants,
                                                     final UDataInputStream bytes)
        throws IOException
    {
        final int attribute_name_index = bytes.readU2 ();
        final long attribute_length = bytes.readU4 ();
        
        final CONSTANT_Utf8_info attribute_name = (CONSTANT_Utf8_info) constants.get (attribute_name_index);
        final String name = attribute_name.m_value;
                
        if (ATTRIBUTE_CODE.equals (name))
        {
            return new CodeAttribute_info (constants, attribute_name_index, attribute_length, bytes);
        }
        else if (ATTRIBUTE_CONSTANT_VALUE.equals (name))
        {
            return new ConstantValueAttribute_info (attribute_name_index, attribute_length, bytes);
        }
        else if (ATTRIBUTE_EXCEPTIONS.equals (name))
        {
            return new ExceptionsAttribute_info (attribute_name_index, attribute_length, bytes);
        }
        else if (ATTRIBUTE_INNERCLASSES.equals (name))
        {
            return new InnerClassesAttribute_info (attribute_name_index, attribute_length, bytes);
        }
        else if (ATTRIBUTE_SYNTHETIC.equals (name))
        {
            return new SyntheticAttribute_info (attribute_name_index, attribute_length);
        }
        else if (ATTRIBUTE_BRIDGE.equals (name))
        {
            return new BridgeAttribute_info (attribute_name_index, attribute_length);
        }
        else if (ATTRIBUTE_LINE_NUMBER_TABLE.equals (name))
        {
            return new LineNumberTableAttribute_info (attribute_name_index, attribute_length, bytes);
        }
        else if (ATTRIBUTE_SOURCEFILE.equals (name))
        {
            return new SourceFileAttribute_info (attribute_name_index, attribute_length, bytes);
        }
        else
        {
            // default:
            return new GenericAttribute_info (attribute_name_index, attribute_length, bytes);
        }
    }
    
    // Cloneable:
    
    /**
     * Chains to super.clone() and removes CloneNotSupportedException
     * from the method signature.
     */
    public Object clone ()
    {
        try
        {
            return super.clone ();
        }
        catch (CloneNotSupportedException e)
        {
            throw new InternalError (e.toString ());
        }
    }
    
    // IClassFormatOutput:
    
    public void writeInClassFormat (UDataOutputStream out) throws IOException
    {   
        out.writeU2 (m_name_index);
        out.writeU4 (length () - 6); // don't use m_attribute_length
    }
    
    // protected: .............................................................

    /*
    protected Attribute_info (UDataInputStream bytes) throws IOException
    {
        //m_name_index = bytes.readU2 ();
        //m_attribute_length = bytes.readU4 ();
    }
    */
        
    protected Attribute_info (final int attribute_name_index, final long attribute_length)
    {
        m_name_index = attribute_name_index;
        m_attribute_length = attribute_length;
    }
    
    // TODO: remove this field as it is invalidated easily by most attribute mutations
    protected long m_attribute_length; // excluding the 6-byte header
    
    // package: ...............................................................

    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------
