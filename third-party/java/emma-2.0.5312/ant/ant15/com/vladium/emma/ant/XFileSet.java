/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: XFileSet.java,v 1.1.1.1 2004/05/09 16:57:28 vlad_r Exp $
 */
package com.vladium.emma.ant;

import java.io.File;

import org.apache.tools.ant.types.FileSet;
import org.apache.tools.ant.types.PatternSet;

// ----------------------------------------------------------------------------
/**
 * An extension of ANT's stock FileSet that adds the convenience of specifying
 * a single 'file' attribute
 * 
 * @author Vlad Roubtsov, (C) 2004
 */
public
final class XFileSet extends FileSet
{
    // public: ................................................................
    
    
    public XFileSet ()
    {
        super ();
    }
    
    public XFileSet (final FileSet fileset)
    {
        super (fileset);
    }
    
    
    // 'file' attribute:
    public void setFile (final File file)
    {
        if (IANTVersion.ANT_1_5_PLUS)
        {
            super.setFile (file);
        }
        else
        {
            if (isReference ()) throw tooManyAttributes ();
        
            final File parent = file.getParentFile ();
            if (parent != null) setDir (parent);
    
            final PatternSet.NameEntry include = createInclude ();
            include.setName (file.getName ());
        }
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
        
} // end of class
// ----------------------------------------------------------------------------