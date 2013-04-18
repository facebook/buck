/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IClassLoadStrategy.java,v 1.1.1.1 2004/05/09 16:57:52 vlad_r Exp $
 */
package com.vladium.util;

// ----------------------------------------------------------------------------
/**
 * The interface implemented by any classloader selection Strategy used
 * with {@link ClassLoaderResolver} API.
 * 
 * @author Vlad Roubtsov, (C) 2003
 */
public
interface IClassLoadStrategy
{
    // public: ................................................................
    
    /**
     * Selects a classloader based on a given load context.
     * 
     * @see ClassLoaderResolver#getClassLoader()
     */
    ClassLoader getClassLoader (ClassLoadContext ctx);

} // end of interface
// ----------------------------------------------------------------------------