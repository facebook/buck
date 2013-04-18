/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: InnerClassesAttribute_info.java,v 1.1.1.1 2004/05/09 16:57:48 vlad_r Exp $
 */
package com.vladium.jcd.cls.attribute;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class InnerClassesAttribute_info extends Attribute_info
{
    // public: ................................................................
    
    // ACCESSORS:
    
    public boolean makesClassNested (final int class_index, final int [] nestedAccessFlags)
    {
        if (class_index > 0)
        {
            // TODO: avoid linear loop by keeping all class indices in a bitset
            
            for (int i = 0, iLimit = size (); i < iLimit; ++ i)
            {
                final InnerClass_info info = get (i);
                
                if (info.m_inner_class_index == class_index)
                {
                    nestedAccessFlags [0] = info.m_inner_access_flags;
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Returns {@link InnerClass_info} descriptor at a given offset.
     * 
     * @param offset inner class entry offset [must be in [0, size()) range;
     * input not checked]
     * @return InnerClass_info descriptor [never null]
     * 
     * @throws IndexOutOfBoundsException if 'offset' is outside of valid range
     */
    public final InnerClass_info get (final int offset)
    {
        return (InnerClass_info) m_classes.get (offset);
    }
    
    /**
     * Returns the number of descriptors in this collection [can be 0].
     */
    public final int size ()
    {
        return m_classes.size ();
    }
    
    public final long length ()
    {
        return 8 + (m_classes.size () << 3); // use size() if class becomes non-final
    }
    
    // Visitor:
    
    public void accept (final IAttributeVisitor visitor, final Object ctx)
    {
        visitor.visit (this, ctx);
    }
    
    public String toString ()
    {
        final StringBuffer s = new StringBuffer ("InnerClassesAttribute_info: [attribute_name_index = " + m_name_index + ", attribute_length = " + length () + "]\n");

        for (int l = 0; l < size (); ++ l)
        {
            s.append ("            " + get (l));
            s.append ("\n"); // TODO: proper EOL const
        }
        
        return s.toString ();
    }
    
    // Cloneable:
    
    /**
     * Performs a deep copy.
     */
    public Object clone ()
    {
        final InnerClassesAttribute_info _clone = (InnerClassesAttribute_info) super.clone ();
        
        final List/* InnerClass_info */ classes = m_classes;
        
        // do deep copy:
        final int class_count = classes.size (); // use size() if class becomes non-final
        _clone.m_classes = new ArrayList (class_count);
        for (int e = 0; e < class_count; ++ e)
        {
            _clone.m_classes.add (((InnerClass_info) classes.get (e)).clone ());
        }
        
        return _clone;
    }

    // IClassFormatOutput:
    
    public void writeInClassFormat (final UDataOutputStream out) throws IOException
    {
        super.writeInClassFormat (out);
        
        final List/* InnerClass_info */ classes = m_classes;
        
        final int class_count = classes.size (); // use size() if class becomes non-final
        out.writeU2 (class_count);
        
        for (int l = 0; l < class_count; ++ l)
        {
            ((InnerClass_info) classes.get (l)).writeInClassFormat (out);
        }
    }
    
    // protected: .............................................................

    // package: ...............................................................


    InnerClassesAttribute_info (final int attribute_name_index, final long attribute_length,
                                final UDataInputStream bytes)
        throws IOException
    {
        super (attribute_name_index, attribute_length);
        
        final int class_count = bytes.readU2 ();
        final List/* InnerClass_info */ classes = new ArrayList (class_count);
        
        for (int i = 0; i < class_count; ++ i)
        {
            classes.add (new InnerClass_info (bytes));
        }
        
        m_classes = classes;
    }
    
    // private: ...............................................................
    
    
    private List/* InnerClass_info */ m_classes; // never null
    
} // end of class
// ----------------------------------------------------------------------------