/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ClassDefParser.java,v 1.1.1.1 2004/05/09 16:57:51 vlad_r Exp $
 */
package com.vladium.jcd.parser;

import java.io.InputStream;
import java.io.IOException;

import com.vladium.jcd.cls.*;
import com.vladium.jcd.cls.attribute.*;
import com.vladium.jcd.cls.constant.*;
import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.util.ByteArrayIStream;

// ----------------------------------------------------------------------------
/**
 * This class provides an API for parsing a stream or array of bytecodes into a
 * {@link ClassDef} AST.
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
abstract class ClassDefParser
{
    // public: ................................................................

    
    /**
     * Parses an array of bytecodes into a {@link ClassDef}.
     */
    public static ClassDef parseClass (final byte [] bytes)
        throws IOException
    {
        if (bytes == null) throw new IllegalArgumentException ("null input: bytes");
        
        classParser parser = new classParser (new UDataInputStream (new ByteArrayIStream (bytes)));
        
        return parser.class_table ();
    }
    
    /**
     * Parses an array of bytecodes into a {@link ClassDef}.
     */
    public static ClassDef parseClass (final byte [] bytes, final int length)
        throws IOException
    {
        if (bytes == null) throw new IllegalArgumentException ("null input: bytes");
        
        classParser parser = new classParser (new UDataInputStream (new ByteArrayIStream (bytes, length)));
        
        return parser.class_table ();
    }
    
    
    /**
     * Parses a stream of bytecodes into a {@link ClassDef}.
     */
    public static ClassDef parseClass (final InputStream bytes)
        throws IOException
    {
        if (bytes == null) throw new IllegalArgumentException ("null input: bytes");
        
        classParser parser = new classParser (new UDataInputStream (bytes));
        
        return parser.class_table ();
    }
    
    // protected: .............................................................

    // package: ...............................................................


    static final boolean PARSE_SERIAL_VERSION_UID = true;    
    
    static final String SERIAL_VERSION_UID_FIELD_NAME   = "serialVersionUID";
    static final int SERIAL_VERSION_UID_FIELD_MASK      = IAccessFlags.ACC_STATIC | IAccessFlags.ACC_FINAL;

    // private: ...............................................................

    
    /**
     * All the parsing work is done by this class and its class_table method. The
     * work that needs to be done is not complicated, but is rather monotonous -- see
     * Chapter 4 of VM spec 1.0 for the class file format.
     */
    private static final class classParser
    {
        classParser (final UDataInputStream bytes)
        {
            m_bytes = bytes;
        }

        
        ClassDef class_table () throws IOException
        {
            m_table = new ClassDef ();
            
            
            magic ();
            version ();
            
            if (DEBUG) System.out.println (s_line);
            
            constant_pool ();
            
            if (DEBUG) System.out.println (s_line);
            
            access_flags ();
            this_class ();
            super_class ();
            
            if (DEBUG) System.out.println (s_line);
            
            interfaces ();
            if (DEBUG) System.out.println (s_line);
            
            fields ();
            if (DEBUG) System.out.println (s_line);
            
            methods ();
            if (DEBUG) System.out.println (s_line);
            
            attributes ();
            if (DEBUG) System.out.println (s_line);
            
            return m_table;
        }
        
        
        void magic () throws IOException
        {
            final long magic = m_bytes.readU4 ();
            if (DEBUG) System.out.println ("magic: [" + Long.toHexString (magic) + ']');
            
            m_table.setMagic (magic);
        }
        
        
        void version () throws IOException
        {
            final int minor_version = m_bytes.readU2 ();
            final int major_version = m_bytes.readU2 ();
            
            if (DEBUG)
            {
                System.out.println ("major_version: [" + major_version + ']');
                System.out.println ("minor_version: [" + minor_version + ']');
            }
            
            m_table.setVersion (new int [] {major_version, minor_version});
        }
        
        
        void constant_pool () throws IOException
        {
            final int constant_pool_count = m_bytes.readU2 ();
            if (DEBUG) System.out.println ("constant_pool_count = " + constant_pool_count + " [actual number of entries = " + (constant_pool_count - 1) + "]");
            
            final IConstantCollection constants = m_table.getConstants();
            
            for (int index = 1; index < constant_pool_count; ++ index)
            {
                final CONSTANT_info cp_info = CONSTANT_info.new_CONSTANT_info (m_bytes);
                constants.add (cp_info);
                
                if (DEBUG) System.out.println ("[" + index + "] constant: " + cp_info);
                
                if ((cp_info instanceof CONSTANT_Long_info) || (cp_info instanceof CONSTANT_Double_info))
                    index++;
            }
        }
        
        
        void access_flags () throws IOException
        {
            final int _access_flags = m_bytes.readU2 ();
            
            m_table.setAccessFlags (_access_flags);
        }
        
        
        void this_class () throws IOException
        {
            final int _class_index = m_bytes.readU2 ();
            if (DEBUG) System.out.println ("this_class: [" + _class_index + ']');
            
            m_table.setThisClassIndex (_class_index);
        }
        
        
        void super_class () throws IOException
        {
            final int _class_index = m_bytes.readU2 ();
            if (DEBUG) System.out.println ("super_class: [" + _class_index + ']');
            
            m_table.setSuperClassIndex (_class_index);
        }
        
        
        void interfaces () throws IOException
        {
            final int _interfaces_count = m_bytes.readU2 ();
            if (DEBUG) System.out.println ("interfaces_count = " + _interfaces_count);
            
            for (int i = 0; i < _interfaces_count; i++)
            {
                int _interface_index = m_bytes.readU2 ();
                if (DEBUG) System.out.println ("[" + i + "] interface: " + _interface_index);
                
                m_table.getInterfaces().add (_interface_index);
            }
        }
        
        
        void fields () throws IOException
        {
            final int _fields_count = m_bytes.readU2 ();
            if (DEBUG) System.out.println ("fields_count = " + _fields_count);
            
            final IConstantCollection constantPool = m_table.getConstants ();
            
            for (int i = 0; i < _fields_count; i++)
            {
                final Field_info field_info = new Field_info (constantPool, m_bytes);
                if (DEBUG)
                {
                    System.out.println ("[" + i + "] field: " + field_info);
                    System.out.println ();
                }
                
                m_table.getFields().add (field_info);
                
                if (PARSE_SERIAL_VERSION_UID)
                
                if (((field_info.getAccessFlags () & SERIAL_VERSION_UID_FIELD_MASK) == SERIAL_VERSION_UID_FIELD_MASK)
                    && SERIAL_VERSION_UID_FIELD_NAME.equals (field_info.getName (m_table)))
                {
                    final IAttributeCollection attributes = field_info.getAttributes ();
                    for (int a = 0, aLimit = attributes.size (); a < aLimit; ++ a)
                    {
                        final Attribute_info attr_info = attributes.get (a);
                        
                        if (attr_info instanceof ConstantValueAttribute_info)
                        {
                            final CONSTANT_literal_info constant_value = ((ConstantValueAttribute_info) attr_info).getValue (m_table);
                            if (constant_value instanceof CONSTANT_Long_info)
                                m_table.setDeclaredSUID (((CONSTANT_Long_info) constant_value).m_value);
                        }
                    }
                }
            }
        }
        
        
        void methods () throws IOException
        {
            final int _methods_count = m_bytes.readU2 ();
            if (DEBUG) System.out.println ("methods_count = " + _methods_count);
            
            final IConstantCollection constantPool = m_table.getConstants ();
            
            for (int i = 0; i < _methods_count; i++)
            {
                final Method_info method_info = new Method_info (constantPool, m_bytes);
                if (DEBUG)
                {
                    System.out.println ("[" + i + "] method: " + method_info);
                    System.out.println ();
                }
                
                m_table.getMethods().add (method_info);
            }
        }
        
        
        void attributes () throws IOException
        {
            final int _attributes_count = m_bytes.readU2 ();
            if (DEBUG) System.out.println ("attributes_count = " + _attributes_count);
            
            IConstantCollection constantPool = m_table.getConstants ();
            
            for (int i = 0; i < _attributes_count; i++)
            {
                Attribute_info attribute_info = Attribute_info.new_Attribute_info (constantPool, m_bytes);
                if (DEBUG)
                {
                    System.out.println ("[" + i + "] attribute: " + attribute_info);
                    System.out.println ();
                }
                
                m_table.getAttributes().add (attribute_info);
            }
        }
        
        
        private final UDataInputStream m_bytes;
        private ClassDef m_table;
    
        private static final boolean DEBUG = false;
        private static final String s_line = "------------------------------------------------------------------------";

    } // end of static class    
    
} // end of class
// ----------------------------------------------------------------------------
