/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: HyperRef.java,v 1.1.1.1 2004/05/09 16:57:41 vlad_r Exp $
 */
package com.vladium.emma.report.html.doc;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public class HyperRef extends IElement.Factory.ElementImpl
{
    // public: ................................................................
    
    public HyperRef (final String href, final String text, final boolean nbsp)
    {
        super (Tag.A, AttributeSet.create ());
        
        if ((href == null) || (href.length () == 0))
            throw new IllegalArgumentException ("null or empty input: href");
        
        if ((text == null) || (text.length () == 0))
            throw new IllegalArgumentException ("null or empty input: text");
        
        getAttributes ().set (Attribute.HREF, href);
        
        // TODO: does href need to be URL-encoded?
        setText (text, nbsp); 
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------