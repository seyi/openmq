/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2000-2012 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.messaging.jmq.jmsserver.core;

import java.io.*;
import java.lang.ref.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Hashtable;
import java.util.Vector;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.Set;
import java.util.Collections;
import java.util.Iterator;

import com.sun.messaging.jmq.io.*;
import com.sun.messaging.jmq.util.lists.*;
import com.sun.messaging.jmq.jmsserver.plugin.spi.ConsumerSpi;
import com.sun.messaging.jmq.jmsserver.plugin.spi.SessionOpSpi;
import com.sun.messaging.jmq.jmsserver.util.BrokerException;
import com.sun.messaging.jmq.jmsserver.data.TransactionUID;
import com.sun.messaging.jmq.jmsserver.data.TransactionState;
import com.sun.messaging.jmq.jmsserver.data.TransactionBroker;
import com.sun.messaging.jmq.jmsserver.Globals;
import com.sun.messaging.jmq.jmsserver.util.lists.*;
import com.sun.messaging.jmq.jmsserver.service.ConnectionUID;
import com.sun.messaging.jmq.jmsserver.service.Connection;
import com.sun.messaging.jmq.jmsserver.service.imq.IMQConnection;
import com.sun.messaging.jmq.jmsserver.resources.*;
import com.sun.messaging.jmq.jmsserver.data.TransactionList;
import com.sun.messaging.jmq.jmsserver.persist.api.PartitionedStore;
import com.sun.messaging.jmq.jmsserver.persist.api.NoPersistPartition;
import com.sun.messaging.jmq.util.log.*;
import com.sun.messaging.jmq.util.JMQXid;



public class SessionOp extends SessionOpSpi
{

    private Logger logger = Globals.getLogger();

    private Map deliveredMessages;

    private Map cleanupList = new HashMap();
    private Map storeMap = new HashMap();
    private DestinationList DL = Globals.getDestinationList();

    private SessionOp(Session s) {
        super(s);
        deliveredMessages = Collections.synchronizedMap(new LinkedHashMap());
    }

    public static SessionOp newInstance(Session s) {
        return new SessionOp(s);
    }

    class ackEntry
    {
        ConsumerUID uid = null;
        ConsumerUID storedcid = null;

        Object pref = null;
        SysMessageID id = null;
        TransactionUID tuid = null;
        int hc = 0;

        public ackEntry(SysMessageID id,
              ConsumerUID uid) 
        { 
             assert id != null;
             assert uid != null;
             this.id = id;
             this.uid = uid;
             pref = null;
        }

        public String toString() {
            return id+"["+uid+","+storedcid+"]"+ (tuid == null? "":"TUID="+tuid);
        }

        public String getDebugMessage(boolean full)
        {
            PacketReference ref = getReference();
            Packet p = (ref == null ? null : ref.getPacket());

            String str = "[" + uid + "," + storedcid + "," +
                    (p == null ? "null" : p.toString()) + "]";
            if (full && p != null) {
                 str += "\n" + p.dumpPacketString(">>");
            }
            return str;
        }

        public void setTUID(TransactionUID uid) {
            this.tuid = uid;
        }
        public TransactionUID getTUID() {
            return tuid;
        }

        public ConsumerUID getConsumerUID() {
            return uid;
        }
        public ConsumerUID getStoredUID() {
            return storedcid;
        }
        public SysMessageID getSysMessageID() {
            return id;
        }
        public PacketReference getReference() {
            if (pref instanceof WeakReference) {
                return (PacketReference)((WeakReference)pref).get();
            } else {
                return (PacketReference)pref;
            }
        }


        public ackEntry(PacketReference ref, 
               ConsumerUID uid, ConsumerUID storedUID) 
        {
            if (ref.isLocal()) {
                pref = new WeakReference(ref);
            } else {
                pref = ref;
            }
            id = ref.getSysMessageID();
            storedcid = storedUID;
            this.uid = uid;
        }

        public PacketReference acknowledged(boolean notify) throws BrokerException {
            return acknowledged(notify, null, null, null, true);
        }

        public PacketReference acknowledged(boolean notify, boolean ackack) throws BrokerException {
            return acknowledged(notify, null, null, null, ackack);
        }

        public PacketReference acknowledged(boolean notify, 
            TransactionUID tid, TransactionList translist, 
            HashMap<TransactionBroker, Object> remoteNotified, boolean ackack) 
            throws BrokerException {

            assert pref != null;
            boolean rm = false;

            PacketReference ref = getReference();

            try {
                if (ref != null && ref.isOverrided()) {
                    BrokerException bex = new BrokerException(
                               "Message requeued:"+ref, Status.GONE);
                    bex.setRemoteConsumerUIDs(String.valueOf(getConsumerUID().longValue()));
                    bex.setRemote(true); 
                    throw bex;
                }
                if (ref == null) {
                    // XXX weird
                    ref = DL.get(null, id);
                }
                if (ref == null) {
                    String emsg = null;
                    if (tid == null) {
                        emsg = Globals.getBrokerResources().getKString(
                        BrokerResources.W_ACK_MESSAGE_GONE, this.toString());
                    } else {
                        emsg = Globals.getBrokerResources().getKString(
                        BrokerResources.W_ACK_MESSAGE_GONE_IN_TXN, tid.toString(),
                                             this.toString());
                    }
                    logger.log(Logger.WARNING, emsg);
                    throw new BrokerException(emsg, Status.CONFLICT);
                }
                rm = ref.acknowledged(uid, storedcid, !session.isUnsafeAck(uid), 
                                      notify, tid, translist, remoteNotified, ackack);
                Consumer c = (Consumer)session.getConsumerOnSession(uid);
                if (c != null) {
                    c.setLastAckTime(System.currentTimeMillis());
                }
            } catch (Exception ex) {
                assert false : ref;
                String emsg = Globals.getBrokerResources().getKString(
                    BrokerResources.X_UNABLE_PROCESS_MESSAGE_ACK, 
                    this.toString()+"["+ref.getDestinationUID()+"]", ex.getMessage());
                if (logger.getLevel() <= Logger.DEBUG) {
                    logger.logStack(Logger.DEBUG, emsg, ex);
                } else {
                    logger.log(Logger.WARNING, emsg);
                }
                if (ex instanceof BrokerException) throw (BrokerException)ex;
                throw new BrokerException(emsg, ex);
            }
            return (rm ? ref : null);
        }

        public boolean equals(Object o) {
            if (! (o instanceof ackEntry)) {
                return false;
            }
            ackEntry ak = (ackEntry)o;
            return uid.equals(ak.uid) &&
                   id.equals(ak.id);
        }
        public int hashCode() {
            // uid is 4 bytes
            if (hc == 0) {
                hc = id.hashCode()*15 + uid.hashCode();
            }
            return hc;
        }
    }

    public ConsumerUID getStoredIDForDetatchedConsumer(ConsumerUID cuid) {
        return (ConsumerUID)storeMap.get(cuid);
    }

    /************************************************
     * Implements SessionOpSpi abstract methods
     *************************************************/

    public Hashtable getDebugState() {
        Hashtable ht = new Hashtable();
        ht.put("TABLE", "SessionOp["+session+"]");
        ht.put("PendingAcks(deliveredMessages)", String.valueOf(deliveredMessages.size()));

        // ok deal w/ unacked per consumer - its easier at this level

        if (deliveredMessages.size() > 0) {
            HashMap copyDelivered = null;
            int cuidCnt[] = null;
            synchronized (deliveredMessages) {
                copyDelivered = new HashMap(deliveredMessages);
            }
            List copyCuids = session.getConsumerUIDs();
            cuidCnt = new int[copyCuids.size()];
    
            Iterator itr = copyDelivered.values().iterator();
            while (itr.hasNext()) {
                ackEntry e = (ackEntry)itr.next();
                int indx = copyCuids.indexOf(e.getConsumerUID());
                if (indx == -1)
                    continue;
                 else
                     cuidCnt[indx] ++;
            }
            Hashtable m = new Hashtable();
            for (int i=0; i < copyCuids.size(); i ++ ) {
                if (cuidCnt[i] == 0) continue;
                ConsumerUID cuid = (ConsumerUID) copyCuids.get(i);
                m.put(String.valueOf(cuid.longValue()), String.valueOf(cuidCnt[i]));
            }
            if (!m.isEmpty())
                ht.put("PendingAcksByConsumer", m);
        }
        return ht;
    }

    public Vector getDebugMessages(boolean full) {
        Vector v = new Vector();
        synchronized (deliveredMessages) {
            Iterator itr = deliveredMessages.values().iterator();
            while (itr.hasNext()) {
                ackEntry e = (ackEntry)itr.next();
                v.add(e.getDebugMessage(full));
            }
        }
        return v;
    }

    public List getPendingAcks(ConsumerUID uid)
    {
        List acks = new ArrayList();
        Map copyDelivered = new HashMap();
        synchronized (deliveredMessages) {
            if (deliveredMessages.size() == 0)
                   return acks;
            copyDelivered.putAll(deliveredMessages);
        }

        Iterator itr = copyDelivered.values().iterator();
        while (itr.hasNext()) {
            ackEntry e = (ackEntry)itr.next();
            if (e.getConsumerUID().equals(uid)) {
                acks.add(e.getSysMessageID());
            }
        }
        return acks;
    }

    public void checkAckType(int type) throws BrokerException {
    }

    /**
     * @return true to deliver the message
     */
    public boolean onMessageDelivery(ConsumerSpi con, Object msg)
    {
        Consumer consumer = (Consumer)con;
        PacketReference ref = (PacketReference)msg;

        ConsumerUID cuid = consumer.getConsumerUID();
        ConsumerUID suid = consumer.getStoredConsumerUID();

        ackEntry entry = null; 
        if (!consumer.getConsumerUID().isNoAck()) {
            entry = new ackEntry(ref, cuid, suid);
            synchronized(deliveredMessages) {
                deliveredMessages.put(entry, entry);
            }
        }

        try  {
             boolean store = !session.isAutoAck(cuid) || deliveredMessages.size() == 1;
            
             if (ref.delivered(cuid, suid, !session.isUnsafeAck(cuid), store)) {
                 try {

                 // either hit limit or need to ack message
                 Destination d = ref.getDestination();
                 //XXX - use destination limit
                 if (ref.isDead()) { // undeliverable
                     Packet pk = ref.getPacket();
                     if (pk != null && !pk.getConsumerFlow()) {
                         ref.removeInDelivery(suid);
                         d.removeDeadMessage(ref);
                         synchronized(deliveredMessages) {
                             deliveredMessages.remove(entry);
                         }
                         return false;
                     }
                 } else { // no ack remove
                     ref.removeInDelivery(suid);
                     d.removeMessage(ref.getSysMessageID(),
                         RemoveReason.ACKNOWLEDGED, !ref.isExpired());
                     synchronized(deliveredMessages) {
                         deliveredMessages.remove(entry);
                     }
                 }

                 } finally {
                     ref.postAcknowledgedRemoval();
                 }
             }

        } catch (Exception ex) {
            logger.logStack(Logger.WARNING, ex.getMessage(), ex);
            synchronized(deliveredMessages) {
                if (entry != null) {
                    deliveredMessages.get(entry);
                }
            }
            return false;
        }
        return true;
    }

    public String toString() {
        return "SessionOp["+session+"]";
    }

    private Set detachedRConsumerUIDs = Collections.synchronizedSet(new LinkedHashSet());

    /**
     * @param id last SysMessageID seen (null indicates all have been seen)
     * @param redeliverAll  ignore id and redeliver all
     * @param redeliverPendingConsume - redeliver pending messages
     */
    public boolean detachConsumer(ConsumerSpi c, SysMessageID id,
           boolean redeliverPendingConsume, boolean redeliverAll, Connection conn)
    {
        Consumer con = (Consumer)c;
        ConsumerUID cuid = con.getConsumerUID();
        ConsumerUID suid = con.getStoredConsumerUID();

        // OK, we have 2 sets of messages:
        //    messages which were seen (and need the state
        //         set to consumed)
        //    messages which were NOT seen (and need the
        //         state reset)
        // get delivered messages
        Set s = new LinkedHashSet();
        HashMap remotePendings = new HashMap();

        boolean holdmsgs = false;

        TransactionList[] tls = DL.getTransactionList(
                                ((IMQConnection)conn).getPartitionedStore());
        TransactionList translist = tls[0];

        // get all the messages for the consumer
        synchronized (deliveredMessages) {
            ackEntry startEntry = null;

            // workaround for client sending ack if acknowledged
            if (id != null) {
                ackEntry entry = new ackEntry(id, cuid);
                startEntry = (ackEntry)deliveredMessages.get(entry);
            }
          
            // make a copy of all of the data
            cleanupList.put(cuid, con.getParentList());
            storeMap.put(cuid, con.getStoredConsumerUID());

            // OK first loop through all of the consumed
            // messages and mark them consumed
            Iterator itr = deliveredMessages.values().iterator();
            boolean found = (startEntry == null && id != null);
            while (!redeliverAll && !found && itr.hasNext()) {
                ackEntry val = (ackEntry)itr.next();
                if (val == startEntry) { 
                     // we are done with consumed messages
                     found = true;
                }
                // see if we are for a different consumer
                //forward port 6829773
                if (!val.storedcid.equals(suid) || !val.uid.equals(cuid))
                    continue;
                PacketReference pr = val.getReference();
                // we know the consumer saw it .. mark it consumed
                if (pr != null) {
                    try {
                        pr.consumed(suid, !session.isUnsafeAck(cuid), session.isAutoAck(cuid));
                    } catch (Exception ex) {
                        Object[] args = { "["+pr+","+suid+"]", cuid, ex.getMessage() }; 
                        logger.log(Logger.WARNING, Globals.getBrokerResources().getKString(
                        BrokerResources.W_UNABLE_UPDATE_REF_STATE_ON_CLOSE_CONSUMER, args), ex);
                    } 
                }
                if (redeliverPendingConsume) {
                    if (pr != null) {
                        pr.removeInDelivery(suid);
                        s.add(pr);
                    }
                    itr.remove();
                    continue;
                } 
                if (session.isTransacted()) { 
                    if (!translist.isConsumedInTransaction(
                            val.getSysMessageID(), val.uid)) {
                        if (pr != null) {
                            pr.removeInDelivery(suid);
                            s.add(pr);
                        }
                        itr.remove();
                        continue;
                    }
                }
                if (pr != null && !pr.isLocal() && session.isValid()) {
                    BrokerAddress ba = pr.getBrokerAddress();   
                    if (ba != null) {
                        List l = (List)remotePendings.get(ba);
                        if (l == null) {
                            l = new ArrayList();
                            remotePendings.put(ba, l);
                        }
                        l.add(pr.getSysMessageID());
                        detachedRConsumerUIDs.add(val.uid);	
                    }
                }
                holdmsgs = true;
            }
            // now deal with re-queueing messages
            while (itr.hasNext()) {
                ackEntry val = (ackEntry)itr.next();
                // see if we are for a different consumer
                if (!val.storedcid.equals(suid)  || !val.uid.equals(cuid))
                    continue;
                PacketReference pr = val.getReference();
                if (session.isTransacted()) { 
                    if (translist.isConsumedInTransaction(
                            val.getSysMessageID(), val.uid)) {
                        if (pr != null && !pr.isLocal() && session.isValid()) {
                            BrokerAddress ba = pr.getBrokerAddress();   
                            if (ba != null) {
                                List l = (List)remotePendings.get(ba);
                                if (l == null) {
                                    l = new ArrayList();
                                    remotePendings.put(ba, l);
                                }
                                l.add(pr.getSysMessageID());
                                detachedRConsumerUIDs.add(val.uid);	
                            }
                        }
                        holdmsgs = true;
                        continue;
                    }
                }
                if ( pr != null) {
                    pr.removeInDelivery(suid);
                    s.add(pr);
                }
                itr.remove();
                try {
                    if (pr != null) {
                        pr.removeDelivered(suid, true);
                     }
                } catch (Exception ex) {
                    logger.log(Logger.WARNING,
                        "Unable to consume " + suid + ":" + pr, ex);
                } 
            }
        }
        con.destroyConsumer(s, remotePendings, 
                            (con.tobeRecreated() || 
                            (!session.isValid() && !session.isXATransacted())),
                            false, true);

        if (!holdmsgs && session.isValid()) {
            synchronized (deliveredMessages) {
                cleanupList.remove(cuid);
                storeMap.remove(cuid);
            }
            return true;
        } 
        return false;   
    }

    public Object ackInTransaction(ConsumerUID cuid, SysMessageID id, 
                                   TransactionUID tuid) 
                                   throws BrokerException {

        // remove from the session pending list
        ackEntry entry = new ackEntry(id, cuid);
        synchronized(deliveredMessages) {
            entry = (ackEntry)deliveredMessages.get(entry);
        }
        if (entry == null) {
            String info = "Received unknown message for transaction " + tuid + " on session " + session + " ack info is " + cuid + "," + id;
            // for debugging see if message exists
            PacketReference m = DL.get(null, id);
            if (m == null) {
                info +=": Broker does not know about the message";
            } else {
                info += ":Broker knows about the message, not associated with the session";
            }
            // send acknowledge
            logger.log(Logger.WARNING, info);
            BrokerException bex = new BrokerException(info, Status.GONE);
            bex.setRemoteConsumerUIDs(String.valueOf(cuid.longValue()));
            bex.setRemote(true);
            throw bex;
        }
        if (entry.getTUID() != null && !entry.getTUID().equals(tuid)) {
            BrokerException bex = new BrokerException(
                "Message requeued:"+entry.getReference(), Status.GONE);
                bex.setRemoteConsumerUIDs(String.valueOf(entry.getConsumerUID().longValue()));
                bex.setRemote(true);
                throw bex;
        }
        PacketReference ref = entry.getReference();
        if (ref == null) {
            throw new BrokerException(Globals.getBrokerResources().
            getKString(BrokerResources.I_ACK_FAILED_MESSAGE_REF_GONE, id)+
            "["+cuid+":"+entry.getStoredUID()+"]TUID="+tuid, Status.CONFLICT);
        }
        if (ref.isOverrided()) {
                BrokerException bex = new BrokerException(
                "Message requeued:"+entry.getReference(), Status.GONE);
                bex.setRemoteConsumerUIDs(String.valueOf(entry.getConsumerUID().longValue()));
                bex.setRemote(true);
                throw bex;
        }
        entry.setTUID(tuid);
        return ref.getBrokerAddress();
    }

    public void close(Connection conn) {
        TransactionList[] tls = DL.getTransactionList(
                                ((IMQConnection)conn).getPartitionedStore());
        TransactionList translist = tls[0];
        // deal w/ old messages
        synchronized(deliveredMessages) {
            if (!deliveredMessages.isEmpty()) {
                // get the list by IDs
                HashMap openMsgs = new HashMap();
                Iterator itr = deliveredMessages.entrySet().iterator();
                while (itr.hasNext()) {
                    Map.Entry entry = (Map.Entry)itr.next();
                    ackEntry e = (ackEntry)entry.getValue();

                    ConsumerUID cuid = e.getConsumerUID();
                    ConsumerUID storeduid = (e.getStoredUID() == null ? cuid:e.getStoredUID()); 

                    // deal w/ orphan messages
                    TransactionUID tid = e.getTUID();
                    if (tid != null) {
                        JMQXid jmqxid = translist.UIDToXid(tid);
                        if (jmqxid != null) {
                            translist.addOrphanAck(tid, e.getSysMessageID(), storeduid, cuid);
                            itr.remove();
                            continue;
                        }
                        TransactionState ts = translist.retrieveState(tid, true);
                        if (ts != null && ts.getState() == TransactionState.PREPARED) {
                            translist.addOrphanAck(tid, e.getSysMessageID(), storeduid, cuid);
                            itr.remove();
                            continue;
                        }
                        if (ts != null && ts.getState() == TransactionState.COMMITTED) {
                            itr.remove();
                            continue;
                        }
                        if (ts != null && conn != null &&
                            ts.getState() == TransactionState.COMPLETE &&
                            conn.getConnectionState() >= Connection.STATE_CLOSED) {
                            String[] args = { ""+tid,
                                              TransactionState.toString(ts.getState()),
                                              session.getConnectionUID().toString() };
                            logger.log(Logger.INFO, Globals.getBrokerResources().getKString(
                                       BrokerResources.I_CONN_CLEANUP_KEEP_TXN, args));
                            translist.addOrphanAck(tid, e.getSysMessageID(), storeduid, cuid);
                            itr.remove();
                            continue;
                        }
                    }
                    PacketReference ref = e.getReference();
                    if (ref == null) ref = DL.get(null, e.getSysMessageID()); //PART
                    if (ref != null && !ref.isLocal()) {
                        itr.remove();
                        try {
                            if ((ref = e.acknowledged(false)) != null) {
                                try {
                                Destination d = ref.getDestination();
                                d.removeRemoteMessage(ref.getSysMessageID(),
                                             RemoveReason.ACKNOWLEDGED, ref);
                                } finally {
                                    ref.postAcknowledgedRemoval();
                                }
                            }
                        } catch(Exception ex) {
                            logger.logStack(session.DEBUG_CLUSTER_MSG ? 
                            Logger.WARNING:Logger.DEBUG, "Unable to clean up remote message "
                            + e.getDebugMessage(false), ex);
                        }
                        continue;
                    }

                    // we arent in a transaction ID .. cool 
                    // add to redeliver list
                    Set s = (Set)openMsgs.get(cuid);
                    if (s == null) {
                        s = new LinkedHashSet();
                        openMsgs.put(cuid, s);
                    }
                    if (ref != null) {
                        ref.removeInDelivery(storeduid);
                    }
                    s.add(e);
                }

                // OK .. see if we ack or cleanup
                itr = openMsgs.keySet().iterator();
                while (itr.hasNext()) {
                    ConsumerUID cuid = (ConsumerUID)itr.next();
                    Map parentmp = (Map)cleanupList.get(cuid);
                    ConsumerUID suid = (ConsumerUID)storeMap.get(cuid);
                    if (parentmp == null || parentmp.size() == 0) {
                        Set s = (Set)openMsgs.get(cuid);
                        Iterator sitr = s.iterator();
                        while (sitr.hasNext()) {
                            ackEntry e = (ackEntry)sitr.next();
                            try {
                                PacketReference ref = e.acknowledged(false);
                                if (ref != null ) {
                                      try {

                                      Destination d= ref.getDestination();
                                      try {
                                          if (ref.isLocal()) {
                                              d.removeMessage(ref.getSysMessageID(),
                                                  RemoveReason.ACKNOWLEDGED);
                                          } else {
                                              d.removeRemoteMessage(ref.getSysMessageID(),
                                                  RemoveReason.ACKNOWLEDGED, ref);
                                          }
                                      } catch (Exception ex) {
                                          logger.logStack(Logger.INFO,"Internal Error", ex);
                                      }

                                      } finally {
                                          ref.postAcknowledgedRemoval();
                                      }
                                }
                            } catch (Exception ex) {
                                // ignore
                            }
                        }
                    } else {
                        Map mp =  new LinkedHashMap();
                        Set msgs = null;
                        PartitionedStore ps = null;
                        Set s = (Set)openMsgs.get(cuid);
                        Iterator sitr = s.iterator();
                        while (sitr.hasNext()) {
                            ackEntry e = (ackEntry)sitr.next();
                            PacketReference ref = e.getReference();
                            if (ref != null) {
                                try {
                                    ref.consumed(suid, !session.isUnsafeAck(cuid), 
                                                 session.isAutoAck(cuid));
                                } catch (Exception ex) {
                                    logger.log(Logger.INFO,"Internal Error " +
                                       "Unable to consume " + suid + ":" + ref, ex);
                                }
                                ps = ref.getPartitionedStore();
                                if (!ref.getDestinationUID().isQueue()) {
                                    ps = new NoPersistPartition(suid);
                                }
                                msgs = (Set)mp.get(ps);
                                if (msgs == null) {
                                    msgs = new LinkedHashSet();
                                    mp.put(ps, msgs);
                                }
                                msgs.add(ref);
                            } else {
                                sitr.remove();
                            }
                        }
                        SubSet pl = null;
                        itr = mp.keySet().iterator();
                        while (itr.hasNext()) {
                            ps = (PartitionedStore)itr.next();
                            pl = (SubSet)parentmp.get(ps);
                            if (pl != null) {
                                ((Prioritized)pl).addAllOrdered((Set)mp.get(ps));
                            } else {
                                logger.log(logger.WARNING, "Message(s) "+mp.get(ps)+"["+suid+", "+cuid+
                                           "] parentlist not found on session closing");
                            }
                        }
                    }   
                }
                deliveredMessages.clear();
                cleanupList.clear();
                storeMap.clear();
            }   
        }

        if (!session.isXATransacted()) {
            Iterator itr = null;
            synchronized(detachedRConsumerUIDs) {
                itr = (new LinkedHashSet(detachedRConsumerUIDs)).iterator();
            }
            while (itr.hasNext()) {
                Consumer c =Consumer.newInstance((ConsumerUID)itr.next()); 
                try {
                    Globals.getClusterBroadcast().destroyConsumer(c, null, true); 
                } catch (Exception e) {
                    logger.log(Logger.WARNING, 
                       "Unable to send consumer ["+c+ 
                       "] cleanup notification for closing of SessionOp["+ this + "].");
                }
            }
        }
    }

    /**
     * If this method returns not null, caller is responsible
     * to call PacketReference.postAcknowledgeRemoval()
     *
     * @see PacketReference#postAcknowledgeRemoval()
     * handles an undeliverable message. This means:
     * <UL>
     *   <LI>removing it from the pending ack list</LI>
     *   <LI>Sending it back to the destination to route </LI>
     * </UL>
     * If the message can not be routed, returns the packet reference
     * (to clean up)
     */
    public Object handleUndeliverable(ConsumerSpi con, SysMessageID id)
    throws BrokerException {

        Consumer c = (Consumer)con;
        ConsumerUID cuid = c.getConsumerUID();

        ackEntry entry = new ackEntry(id, cuid);
        PacketReference ref = null;
        synchronized(deliveredMessages) {
            entry = (ackEntry)deliveredMessages.remove(entry);
        }
        if (entry == null) {
            return null;
        }
        ref = entry.getReference();
        if (ref == null) {
           // already gone
           return null;
        }
        ConsumerUID storedid = c.getStoredConsumerUID();
        if (storedid.equals(cuid)) {
            //not a durable or receiver, nothing to do
            try {
                if (ref.acknowledged(cuid, storedid, false, false)) {
                    return ref;
                }
            } catch (Exception ex) {
                logger.logStack(Logger.DEBUG,"Error handling undeliverable", ex);
            }
            return null;
        }
        // handle it like an orphan message
        // this re-queues it on the durable or queue
        Destination d = ref.getDestination();
        if (ref.isLocal()) {
            d.forwardOrphanMessage(ref, storedid);
        } else {
            if (ref.markDead(cuid, storedid, null, null,
                     RemoveReason.UNDELIVERABLE, -1, null)) {
                return ref;
            }
        }
        return null;
    }

    /**
     * If this method returns not null, caller is responsible
     * to call PacketReference.postAcknowledgeRemoval()
     *
     * @see PacketReference#postAcknowledgedRemoval()
     *
     * handles an undeliverable message. This means:
     * <UL>
     *   <LI>removing it from the pending ack list</LI>
     *   <LI>Sending it back to the destination to route </LI>
     * </UL>
     * If the message can not be routed, returns the packet reference
     * (to clean up)
     */
    public Object handleDead(ConsumerSpi con,
           SysMessageID id, RemoveReason deadReason, Throwable thr, 
           String comment, int deliverCnt)
           throws BrokerException {

        Consumer c = (Consumer)con;
        ConsumerUID cuid = c.getConsumerUID();

        ackEntry entry = new ackEntry(id, cuid);
        PacketReference ref = null;
        synchronized(deliveredMessages) {
            entry = (ackEntry)deliveredMessages.remove(entry);
        }
        if (entry == null) {
            return null;
        }
        ref = entry.getReference();
        if (ref == null) {
           // already gone
           return null;
        }
        ConsumerUID storedid = c.getStoredConsumerUID();
        Destination d = ref.getDestination();
        if (ref.markDead(cuid, storedid, comment, thr, 
                         deadReason, deliverCnt, null)) {
            return ref;
        }
        return null;
    }

    /**
     * Caller must call postAckMessage() immediately after this call
     * and if this method returns not null, caller is responsible 
     * to call PacketReference.postAcknowledgeRemoval()
     *
     * @see PacketReference#postAcknowledgedRemoval()
     */
    public Object ackMessage(ConsumerUID cuid, SysMessageID id,
        TransactionUID tuid, Object translist, 
        HashMap remoteNotified, boolean ackack) 
        throws BrokerException {

        ackEntry entry = new ackEntry(id, cuid);
        PacketReference ref = null;
        synchronized(deliveredMessages) {
            entry = (ackEntry)deliveredMessages.remove(entry);
        }
        if (entry == null) {
            String emsg = null;
            if (tuid == null) {
                emsg = Globals.getBrokerResources().getKString(
                       BrokerResources.W_ACK_MESSAGE_GONE, id+"["+cuid+"]");
            } else {
                emsg = Globals.getBrokerResources().getKString(
                       BrokerResources.W_ACK_MESSAGE_GONE_IN_TXN,
                           tuid.toString(), id+"["+cuid+"]");
            }
            logger.log(Logger.WARNING, emsg);
            throw new BrokerException(emsg, Status.CONFLICT);
        }
        ref = entry.acknowledged(true, tuid, (TransactionList)translist,
                  (HashMap<TransactionBroker, Object>)remoteNotified, ackack);
        return ref;
    }

    public void postAckMessage(ConsumerUID cuid, SysMessageID id, boolean ackack) {
        if (session.isAutoAck(cuid)) {
            synchronized(deliveredMessages) {
                Iterator itr = deliveredMessages.values().iterator();
                while (itr.hasNext()) {
                    ackEntry e= (ackEntry)itr.next();
                    PacketReference newref = e.getReference();
                    if (newref == null) {
                        // see if we can get it by ID
                        newref = DL.get(null, id);
                        if (newref == null) {
                            logger.log(Logger.DEBUGMED, "Removing gone reference "+e);
                        } else {
                            logger.log(Logger.INFO, "Reference still exists despite null in session ack entry: " + newref);
                            // acknowledge it
                            try {
                                PacketReference pr = e.acknowledged(true, ackack);
                                if (pr != null) {
                                    pr.postAcknowledgedRemoval();
                                }
                            } catch (Exception ex) {
                            }
                        }
                        itr.remove();
                    } else {
                     
                        try {
                            newref.delivered(e.getConsumerUID(), e.getStoredUID(), 
                                             true, newref.isStored());
                            break;
                        } catch (Exception ex) {
                            logger.logStack(Logger.WARNING, 
                                Globals.getBrokerResources().getKString(
                                   BrokerResources.W_UNABLE_UPDATE_MSG_DELIVERED_STATE,
                                   newref+"["+cuid+"]", ex.getMessage()), ex);
                        }
                    }
                }
            }
        }
    }

    /**
     * @param ackack whether client requested ackack
     */
    public boolean acknowledgeToMessage(ConsumerUID cuid, SysMessageID id, boolean ackack) 
        throws BrokerException
    {
        boolean removed = false;
        ackEntry entry = new ackEntry(id, cuid);
        synchronized(deliveredMessages) {
            ackEntry value = (ackEntry)deliveredMessages.get(entry);

            if (value == null)  {
                assert false : entry;
                return false;
            }

            Iterator itr = deliveredMessages.values().iterator();
            while (itr.hasNext()) {
                ackEntry val = (ackEntry)itr.next();
                PacketReference ref = val.acknowledged(true, ackack);
                if (ref != null) {
                    try {

                    Destination d= ref.getDestination();
                    try {
                        d.removeMessage(ref.getSysMessageID(),
                                    RemoveReason.ACKNOWLEDGED);
                    } catch (Exception ex) {
                        logger.logStack(Logger.INFO, "Internal Error", ex);
                    }

                    } finally {
                        ref.postAcknowledgedRemoval();
                    }

                }
                itr.remove();
                removed = true;
                if (val.equals(value)) {
                    break;
                }
            }
        }
        return removed;
    }

}


