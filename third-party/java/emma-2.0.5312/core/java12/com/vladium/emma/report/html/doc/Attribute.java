/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: Attribute.java,v 1.1.1.1 2004/05/09 16:57:41 vlad_r Exp $
 */
package com.vladium.emma.report.html.doc;

import com.vladium.util.asserts.$assert;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class Attribute implements IContent
{
    // public: ................................................................
    
    public static final Attribute ID = new AttributeImpl ("ID");
    public static final Attribute NAME = new AttributeImpl ("NAME");
    public static final Attribute TITLE = new AttributeImpl ("TITLE");
    public static final Attribute TYPE = new AttributeImpl ("TYPE");
    public static final Attribute CLASS = new AttributeImpl ("CLASS");
    public static final Attribute HTTP_EQUIV = new AttributeImpl ("HTTP-EQUIV");
    public static final Attribute CONTENT = new AttributeImpl ("CONTENT");
    public static final Attribute HREF = new AttributeImpl ("HREF");
    public static final Attribute SRC = new AttributeImpl ("SRC");
    public static final Attribute REL = new AttributeImpl ("REL");
    public static final Attribute WIDTH = new AttributeImpl ("WIDTH");
    public static final Attribute SIZE = new AttributeImpl ("SIZE");
    public static final Attribute BORDER = new AttributeImpl ("BORDER");
    public static final Attribute CELLPADDING = new AttributeImpl ("CELLPADDING");
    public static final Attribute CELLSPACING = new AttributeImpl ("CELLSPACING");
    public static final Attribute ALIGN = new AttributeImpl ("ALIGN");
    public static final Attribute COLSPAN = new AttributeImpl ("COLSPAN");
    
    public abstract String getName ();
    
    public abstract boolean equals (final Object rhs);
    public abstract int hashCode ();
    
    
    // protected: .............................................................

    // package: ...............................................................
    
    
    Attribute () {}
    
    // private: ...............................................................
    
    
    private static final class AttributeImpl extends Attribute
    {
        
        public boolean equals (final Object rhs)
        {
            if (this == rhs) return true;
            if (! (rhs instanceof AttributeImpl)) return false;
            
            return m_name.equals (((AttributeImpl) rhs).m_name); 
        }
        
        public int hashCode ()
        {
            return m_name.hashCode ();
        }
        
        public String toString ()
        {
            return m_name;
        }
        
        public void emit (final HTMLWriter out)
        {
            out.write (m_name); // no need to escape anything
        }
        
        public String getName ()
        {
            return m_name;
        }
        
        
        AttributeImpl (final String name)
        {
            if ($assert.ENABLED) $assert.ASSERT (name != null, "name = null");
            
            m_name = name;
        }

        
        private final String m_name;
        
    } // end of nested class 

} // end of class
// ----------------------------------------------------------------------------