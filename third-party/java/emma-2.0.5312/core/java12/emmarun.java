/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: emmarun.java,v 1.1.1.1.2.1 2004/07/16 23:32:29 vlad_r Exp $
 */
import com.vladium.emma.Command;
import com.vladium.emma.EMMARuntimeException;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class emmarun
{
    // public: ................................................................
    
    // TODO: set m_out consistently with LoggerInit    
    
    public static void main (final String [] args)
        throws EMMARuntimeException
    {
        final Command command = Command.create ("run", emmarun.class.getName (), args);
        command.run ();
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------