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
 * %W% %G%
 */ 

package com.sun.messaging.jmq.jmsserver.data.handlers;

import java.util.*;
import java.io.*;
import java.net.*;
import com.sun.messaging.jmq.jmsserver.data.PacketHandler;
import com.sun.messaging.jmq.io.*;
import com.sun.messaging.jmq.util.DestType;
import com.sun.messaging.jmq.jmsserver.service.Connection;
import com.sun.messaging.jmq.jmsserver.core.Producer;
import com.sun.messaging.jmq.jmsserver.core.ProducerUID;
import com.sun.messaging.jmq.jmsserver.core.DestinationUID;
import com.sun.messaging.jmq.jmsserver.core.Destination;
import com.sun.messaging.jmq.jmsserver.core.DestinationList;
import com.sun.messaging.jmq.jmsserver.core.Session;
import com.sun.messaging.jmq.jmsserver.core.SessionUID;
import com.sun.messaging.jmq.jmsserver.resources.BrokerResources;
import com.sun.messaging.jmq.jmsserver.util.BrokerException;
import com.sun.messaging.jmq.jmsserver.Globals;
import com.sun.messaging.jmq.jmsserver.service.imq.IMQConnection;
import com.sun.messaging.jmq.jmsserver.service.imq.IMQBasicConnection;
import com.sun.messaging.jmq.jmsserver.memory.MemoryGlobals;
import com.sun.messaging.jmq.util.log.*;



/**
 * Handles the create Message
 */
public class ProducerHandler extends PacketHandler 
{
    private DestinationList DL = Globals.getDestinationList();

    public ProducerHandler() {
    }

    /**
     * Method to handle Producers
     */
    public boolean handle(IMQConnection con, Packet msg) 
        throws BrokerException 
    {

        Packet reply = new Packet(con.useDirectBuffers());
        reply.setPacketType(msg.getPacketType() + 1);
        reply.setConsumerID(msg.getConsumerID());

        boolean isIndemp = msg.getIndempotent();

        int status = Status.OK;
        String reason = null;

        Hashtable props = null;
        try {
            props = msg.getProperties();
        } catch (Exception ex) {
            throw new RuntimeException("Can not load props", ex);
        }

        Hashtable returnprop = new Hashtable();

        Destination d = null;

        try {

            if (msg.getPacketType() == PacketType.ADD_PRODUCER) {
    
                String dest = (String)props.get("JMQDestination");
                Integer type = (Integer)props.get("JMQDestType");
    
                if (!con.isAdminConnection() && MemoryGlobals.getMEM_DISALLOW_PRODUCERS()) {
                    status = Status.ERROR;
                    reason = "Low memory";
                    logger.log(Logger.WARNING,BrokerResources.W_LOW_MEM_REJECT_PRODUCER);
                    throw new BrokerException(reason, status);
                }
                Long lsessionid = (Long)props.get("JMQSessionID");
                if (lsessionid != null) { // 3.5 protocol
                    SessionUID sessionID = new SessionUID(
                               lsessionid.longValue());
                    // single threaded .. we dont have to worry about
                    // someone else creating it
                    Session session = con.getSession(sessionID);
                    if (session == null) {
                        throw new BrokerException("Internal Error: client sent "
                              + "invalid sessionUID w/ ADD_PRODUCER " 
                               + sessionID + " session does not exist");
                    }
                }
                Destination[] ds= null;
                DestinationUID duid = null;
                if (dest != null && !DestinationUID.isWildcard(dest) && type != null) {
                    while (true) {
                        ds = DL.getDestination(con.getPartitionedStore(), dest, 
                            type.intValue(), true, 
                            !con.isAdminConnection());
                        d = ds[0];
                        if (d != null) {
                            try {
                                d.incrementRefCount();
                            } catch (BrokerException ex) {
                                // was destroyed under me
                                // try again
                                continue;
                            } catch (IllegalStateException ex) {
                                throw new BrokerException(
                                    Globals.getBrokerResources().getKString(
                                    BrokerResources.X_SHUTTING_DOWN_BROKER),
                                    BrokerResources.X_SHUTTING_DOWN_BROKER,
                                    ex,
                                    Status.ERROR);
                            } 
                         }
                         break; // got a lock on the dest
                    }
                    if (d == null) {
                        logger.log(Logger.DEBUG, "Unable to add "
                            + "producer to "  + dest 
                            + " :" + DestType.toString(type.intValue())
                            + " destination can not be autocreated ");
                        reason = "can not create destination";
                        status = Status.NOT_FOUND;
                        throw new BrokerException(reason, status);
                    }
                    duid = d.getDestinationUID();
                } else if (dest == null || type == null) {
                    reason = "no destination passed [dest,type] = [" +
                           dest + "," + type + "]";
                    status = Status.ERROR;
                    throw new BrokerException(reason, status);
                } else {
                    duid = DestinationUID.getUID(dest, DestType.isQueue(type.intValue()));
                }

                String info = msg.getSysMessageID().toString();
                Producer p = addProducer(duid, con, info, isIndemp);
   
                ProducerUID pid = p.getProducerUID();
    
                assert pid != null;
   
                // LKS - XXX - REVISIT - WHAT ABOUT FLOW CONTROL 
                boolean active = (d == null ? true : d.isProducerActive(pid));
             
                returnprop.put("JMQProducerID", Long.valueOf(pid.longValue()));
                returnprop.put("JMQDestinationID", duid.toString());
                if (d == null) {
                    returnprop.put("JMQBytes", Long.valueOf(-1));
                    returnprop.put("JMQSize", Integer.valueOf(-1));
                } else if (active) {
                    returnprop.put("JMQBytes", Long.valueOf(d.getBytesProducerFlow()));
                    returnprop.put("JMQSize", Integer.valueOf(d.getSizeProducerFlow()));
                } else {
                    returnprop.put("JMQBytes", Long.valueOf(0));
                    returnprop.put("JMQSize", Integer.valueOf(0));
                }
    
            } else {
                assert msg.getPacketType() == PacketType.DELETE_PRODUCER;
    
                Long pid_l = (Long)props.get("JMQProducerID");
    
                ProducerUID pid = new ProducerUID( pid_l == null ? 0
                                   : pid_l.longValue());
    
                removeProducer(pid, isIndemp, con,
                    "Producer closed requested:\n\tconnection: "
                    + con.getConnectionUID() + "\n\tproducerID: " + pid +
                    "\n\trequest sysmsgid message: " + msg.getSysMessageID());
            }

        } catch (BrokerException ex) {
            status = ex.getStatusCode();
            reason = ex.getMessage();
            logger.log(Logger.INFO, reason);
        } catch (Exception ex) {
            logger.logStack(Logger.INFO,
                 BrokerResources.E_INTERNAL_BROKER_ERROR,
                 "producer message ", ex);
            reason = ex.getMessage();
            status = Status.ERROR;
        } finally {
            if (d != null)
                d.decrementRefCount();
        }


        returnprop.put("JMQStatus", Integer.valueOf(status));
        if (reason != null)
            returnprop.put("JMQReason", reason);
        if (((IMQBasicConnection)con).getDumpPacket() ||
                ((IMQBasicConnection)con).getDumpOutPacket()) 
            returnprop.put("JMQReqID", msg.getSysMessageID().toString());


        reply.setProperties(returnprop);
        con.sendControlMessage(reply);
        return true;
    }

    public Producer addProducer(DestinationUID duid, IMQConnection con, String id, boolean isIndemp)
        throws BrokerException
    {
    

        Producer p = null;
        boolean processed = false;
        if (isIndemp) {
             p =  (Producer)Producer.getProducer(id);

        }
        if (p == null) {
            p = Producer.createProducer(duid,
                 con.getConnectionUID(), id, con.getPartitionedStore());
            assert p != null;
    
  
 
            con.addProducer(p);
   
            // Add to all destinations
            List[] ll = DL.findMatchingIDs(con.getPartitionedStore(),duid);
            List l = ll[0];
            Iterator itr = l.iterator();
            DestinationUID realuid = null;
            Destination[] ds = null;
            Destination d = null;
            while (itr.hasNext()) {
                realuid = (DestinationUID)itr.next();
                ds = DL.getDestination(con.getPartitionedStore(),realuid);
                d = ds[0];
                if (duid.isWildcard() && d.isTemporary()) {
                      logger.log(Logger.DEBUG,"L10N-XXX: Wildcard production with destination name of "
                          + duid +  " to temporary destination " +
                          d.getUniqueName() + " is not supported, ignoring");
                      continue;
                }
                if (duid.isWildcard() && d.isInternal()) {
                     logger.log(Logger.DEBUG,"L10N-XXX: Wildcard production with destination name of "
                        + duid +  " to internal destination " +
                        d.getUniqueName() + " is not supported, ignoring");
                     continue;
                }

                if (duid.isWildcard() && d.isDMQ() ) {
                    logger.log(Logger.DEBUG,"L10N-XXX: Wildcard production with destination name of "
                        + duid +  " to the DeadMessageQueue" +
                        d.getUniqueName() + " is not supported, ignoring");
                    continue;
                }

                d.addProducer(p);
            }
        }

        return p;
    }

    public void removeProducer( ProducerUID pid, boolean isIndemp, 
            IMQConnection con, String msg)
        throws BrokerException
    {
        String reason = null; 
        int status= Status.OK;
        Producer p = (Producer)Producer.getProducer(pid);

        if (p == null && isIndemp) {
            // dont flag error, we already processed it
        } else if (p == null) {
            logger.log(Logger.INFO,
               BrokerResources.E_INTERNAL_BROKER_ERROR,
               "Internal error Unable to find producer "
                 + pid + "\n\t checking if producer was removed recently " +
                  Producer.checkProducer(pid));
            reason = "unknown producer";
            status = Status.ERROR;
            throw new BrokerException(reason, status);
        } else if (p.getConnectionUID() != con.getConnectionUID()) {
            logger.log(Logger.INFO,
               BrokerResources.E_INTERNAL_BROKER_ERROR,
                " error connection "
                 + "removing producer it doesnt own" 
                 + "\n\tPID=" + pid 
                 + "\n\tconnectionUID of request " + con.getConnectionUID()
                 + "\n\tconnectionUID of creator " + p.getConnectionUID()
                 + "\n\tchecking producer state: " +
                  Producer.checkProducer(pid));
            reason = "unknown producer";
            status = Status.ERROR;
            throw new BrokerException(reason, status);
        } else {
            con.removeProducer(pid, msg, coreLifecycle);
        }
    }

}
