/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: AttributeSet.java,v 1.1.1.1 2004/05/09 16:57:41 vlad_r Exp $
 */
package com.vladium.emma.report.html.doc;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.vladium.util.Strings;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class AttributeSet implements IContent
{
    // public: ................................................................
    
    public static AttributeSet create ()
    {
        return new AttributeSetImpl ();
    }
    
    // ACCESSORS:
    
    public abstract boolean isEmpty ();
    
    // MUTATORS:
    
    public abstract AttributeSet set (Attribute attr, String value);
    public abstract AttributeSet set (Attribute attr, int value);
    
    // protected: .............................................................

    // package: ...............................................................
    
    
    AttributeSet () {}
    
    // private: ...............................................................
    
    
    private static final class AttributeSetImpl extends AttributeSet
    {
        public void emit (final HTMLWriter out)
        {
            boolean first = true;
            for (Iterator a = m_attrMap.entrySet ().iterator (); a.hasNext (); )
            {
                final Map.Entry entry = (Map.Entry) a.next ();
                
                final Attribute attr = (Attribute) entry.getKey ();
                final String value = entry.getValue ().toString ();
                
                if (first)
                    first = false;
                else
                    out.write (' ');
                    
                out.write (attr.getName ());
                out.write ("=\"");

                if ((m_buf != null) && (m_buf.length () <= MAX_BUF_LENGTH))
                    m_buf.setLength (0);
                else
                    m_buf = new StringBuffer ();
                
                Strings.HTMLEscape (value, m_buf);
                out.write (m_buf.toString ());
                
                out.write ('\"');
            }
        }
        
        public boolean isEmpty ()
        {
            return m_attrMap.isEmpty ();
        }

        
        public AttributeSet set (final Attribute attr, final String value) // null removes?
        {
            m_attrMap.put (attr, value);
            
            return this;
        }
        
        public AttributeSet set (final Attribute attr, final int value)
        {
            m_attrMap.put (attr, new Integer (value)); // TODO: use int factory here
            
            return this;
        }
        
        
        AttributeSetImpl ()
        {
            m_attrMap = new HashMap ();
        }
        
        // TODO: consider lazy-initing this
        private final Map /* Attribute->String|Integer */ m_attrMap; // never null
        private StringBuffer m_buf; // reused by emit() 
        
        private static final int MAX_BUF_LENGTH = 4 * 1024;
        
    } // end of nested class

} // end of class
// ----------------------------------------------------------------------------