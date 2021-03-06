/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2000-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

/*
 * @(#)GenerateUIDHandler.java	1.11 06/28/07
 */ 

package com.sun.messaging.jmq.jmsserver.common.handlers;

import java.nio.ByteBuffer;
import java.util.Hashtable;

import com.sun.messaging.jmq.util.UniqueID;
import com.sun.messaging.jmq.util.UID;
import com.sun.messaging.jmq.util.log.Logger;
import com.sun.messaging.jmq.io.Packet;
import com.sun.messaging.jmq.io.PacketType;
import com.sun.messaging.jmq.io.Status;
import com.sun.messaging.jmq.jmsserver.Globals;
import com.sun.messaging.jmq.jmsserver.service.imq.IMQConnection;
import com.sun.messaging.jmq.jmsserver.service.imq.IMQBasicConnection;

import com.sun.messaging.jmq.jmsserver.data.PacketHandler;
import com.sun.messaging.jmq.jmsserver.util.BrokerException;


/**
 * Handler class which deals with the GenerateUID packet.
 */
public class GenerateUIDHandler extends PacketHandler 
{
    private Logger logger = Globals.getLogger();
    private static boolean DEBUG = false;

    public GenerateUIDHandler()
    {
        
    }

    /**
     * Method to handle GenerateUID packet.
     * We generate one or more unique ID's and return them in the body
     * of the reply.
     */
    public boolean handle(IMQConnection con, Packet msg) 
        throws BrokerException 
    { 

         if (DEBUG) {
             logger.log(Logger.DEBUGHIGH, "GenerateUIDHandler: handle() [ Received GenerateUID Packet]");
          }

          Hashtable props = null;
          try {
              props = msg.getProperties();
          } catch (Exception ex) {
              logger.logStack(Logger.WARNING, "GEN-UID Packet.getProperties()", ex);
              props = new Hashtable();
          }

          Integer value = null; 
          int quantity = 1;
          if (props != null) {
              value = (Integer)props.get("JMQQuantity");
              if (value != null) {
                quantity = value.intValue();
              }
          }

	  int status = Status.OK;

          // Each UID is a long (8 bytes);
          int size = quantity * 8;

          ByteBuffer body = ByteBuffer.allocate(size);

          // Get the prefix used by this broker and allocate IDs.
          // We could also do this by creating new jmq.util.UID's, but by
          // doing it this way we avoid the object creation overhead.
          short prefix = UID.getPrefix();
          for (int n = 0; n < quantity; n++) {
            body.putLong(UniqueID.generateID(prefix));
          }

          props = new Hashtable();
          props.put("JMQStatus", Integer.valueOf(status));
          props.put("JMQQuantity", Integer.valueOf(quantity));
          if (((IMQBasicConnection)con).getDumpPacket() ||
              ((IMQBasicConnection)con).getDumpOutPacket())
              props.put("JMQReqID", msg.getSysMessageID().toString());


          // Send reply 
          Packet pkt = new Packet(con.useDirectBuffers());
          pkt.setPacketType(PacketType.GENERATE_UID_REPLY);
          pkt.setConsumerID(msg.getConsumerID());
          pkt.setProperties(props);
          body.rewind();
          pkt.setMessageBody(body);

	  con.sendControlMessage(pkt);

          return true;
    }
}
