/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IElementList.java,v 1.1.1.1 2004/05/09 16:57:41 vlad_r Exp $
 */
package com.vladium.emma.report.html.doc;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
interface IElementList extends IContent
{
    // public: ................................................................
    
    IElementList add (IContent content);
    IElementList add (int index, IContent content);
    int size (); // element count 
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------