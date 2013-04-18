/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: Tag.java,v 1.1.1.1 2004/05/09 16:57:41 vlad_r Exp $
 */
package com.vladium.emma.report.html.doc;

import com.vladium.util.asserts.$assert;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class Tag implements IContent
{
    // public: ................................................................
    
    public static final Tag HTML = new TagImpl ("HTML");
    public static final Tag HEAD = new TagImpl ("HEAD");
    public static final Tag BODY = new TagImpl ("BODY");
    public static final Tag META = new TagImpl ("META");
    public static final Tag STYLE = new TagImpl ("STYLE");
    
    public static final Tag TITLE = new TagImpl ("TITLE");
    public static final Tag H1 = new TagImpl ("H1");
    public static final Tag H2 = new TagImpl ("H2");
    public static final Tag H3 = new TagImpl ("H3");
    public static final Tag H4 = new TagImpl ("H4");
    public static final Tag H5 = new TagImpl ("H5");
    public static final Tag H6 = new TagImpl ("H6");
    public static final Tag LINK = new TagImpl ("LINK");
    
    public static final Tag A = new TagImpl ("A");
    
    public static final Tag TABLE = new TagImpl ("TABLE");
    public static final Tag CAPTION = new TagImpl ("CAPTION");
    public static final Tag TH = new TagImpl ("TH");
    public static final Tag TR = new TagImpl ("TR");
    public static final Tag TD = new TagImpl ("TD");
    
    public static final Tag HR = new TagImpl ("HR");
    public static final Tag P = new TagImpl ("P");
    public static final Tag SPAN = new TagImpl ("SPAN");
    
    public static final Tag [] Hs = new Tag [] {H1, H2, H3, H4, H4, H6};
    
    public abstract String getName ();
    
    // protected: .............................................................

    // package: ...............................................................
    
    
    Tag () {}
    
    // private: ...............................................................
    
    private static final class TagImpl extends Tag
    {
        public void emit (final HTMLWriter out)
        {
            out.write (m_name);
        }
        
        public String getName ()
        {
            return m_name;
        }
        
        public String toString ()
        {
            return m_name;
        }
        
        TagImpl (final String name)
        {
            if ($assert.ENABLED) $assert.ASSERT (name != null, "name = null");
            
            m_name = name;
        }
        
        
        private final String m_name;
        
    } // end of 

} // end of class
// ----------------------------------------------------------------------------