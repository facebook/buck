/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IMergeable.java,v 1.1.1.1 2004/05/09 16:57:31 vlad_r Exp $
 */
package com.vladium.emma.data;

import java.io.Serializable;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
interface IMergeable extends Serializable
{
    // public: ................................................................
    
    boolean isEmpty ();
    
    /**
     * Caller must always switch to the returned handle. 
     */
    IMergeable merge (IMergeable data);

} // end of interface
// ----------------------------------------------------------------------------