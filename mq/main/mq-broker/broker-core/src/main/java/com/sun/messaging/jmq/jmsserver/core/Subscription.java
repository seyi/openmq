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

import java.util.*;
import java.io.*;
import com.sun.messaging.jmq.io.SysMessageID;
import com.sun.messaging.jmq.io.Status;
import com.sun.messaging.jmq.util.log.*;
import com.sun.messaging.jmq.util.lists.*;
import com.sun.messaging.jmq.util.DestType;
import com.sun.messaging.jmq.util.selector.*;
import com.sun.messaging.jmq.jmsserver.FaultInjection;
import com.sun.messaging.jmq.jmsserver.util.BrokerException;
import com.sun.messaging.jmq.jmsserver.util.PartitionNotFoundException;
import com.sun.messaging.jmq.jmsserver.util.ConsumerAlreadyAddedException;
import com.sun.messaging.jmq.jmsserver.service.Connection;
import com.sun.messaging.jmq.jmsserver.service.imq.IMQConnection;
import com.sun.messaging.jmq.jmsserver.service.ConnectionManager;
import com.sun.messaging.jmq.jmsserver.resources.*;
import com.sun.messaging.jmq.jmsserver.persist.api.LoadException;
import com.sun.messaging.jmq.jmsserver.persist.api.ChangeRecordInfo;
import com.sun.messaging.jmq.jmsserver.util.ConflictException;
import com.sun.messaging.jmq.jmsserver.Globals;
import com.sun.messaging.jmq.jmsserver.plugin.spi.SubscriptionSpi;
import com.sun.messaging.jmq.jmsserver.persist.api.NoPersistPartition;
import com.sun.messaging.jmq.jmsserver.persist.api.PartitionedStore;
import java.net.URLEncoder;
import com.sun.messaging.jmq.util.CacheHashMap;

import com.sun.messaging.jmq.jmsserver.service.ConnectionUID;

/**
 * A subscription represents a "durable subscriber (shared or
 * un-shared)" or a "shared non-durable consumer"
 */

public class Subscription extends Consumer implements SubscriptionSpi
{
    static final long serialVersionUID = -6794838710921895217L;


    /**
     * cache containing clientid/durable name pairs which were
     * removed because the destination was destroyed w/ active
     * consumers
     * bug 6157279
     */
    private static CacheHashMap cache = new CacheHashMap(20);

    /**
     * list of all durable subscriptions
     */
    static Map<String, Subscription> durableList = 
               new LinkedHashMap<String, Subscription>();

    /**
     * list of all non-durable subscriptions
     */
    static Map nonDurableList = new HashMap();

    /**
     * is this a shared non-durable or a durable subscription
     */
    boolean isDurable = true;

    /**
     * current active consumers
     */
    transient Map activeConsumers = null;

    /**
     * has the durable been stored
     */
    transient boolean stored = false;

    /**
     * SubSet used as the parent list for child subscribers
     */
    SubSet msgsSubset = null;

    /**
     * maximum # of active subscribers
     */
    int maxNumActiveConsumers = 1;
    
    /**
     * general lock used by the class
     */
    transient Object subLock = new Object();

    /**
     * JMS durable name.
     */
    protected String durableName = null;

    /**
     * JMS client id.
     */
    protected String clientID = null;

    /**
     * calculated hashcode value
     */
    int hashcode = 0;

    private transient Map<Integer, ChangeRecordInfo> currentChangeRecordInfo =
        Collections.synchronizedMap(new HashMap<Integer, ChangeRecordInfo>());

    private transient FaultInjection fi = FaultInjection.getInjection();

    public ChangeRecordInfo getCurrentChangeRecordInfo(int type) {
        return currentChangeRecordInfo.get(Integer.valueOf(type));
    }

    public void setCurrentChangeRecordInfo(int type, ChangeRecordInfo cri) {
        currentChangeRecordInfo.put(Integer.valueOf(type), cri);
    }

    public boolean isDurable() {
        return isDurable;
    }

    public int numInProcessMsgs() {
        // note: may be slightly off in a running system
        // but I don't want to lock
        int queued = msgs.size();
        queued += numPendingAcks();
        return queued;
    }

    public int numPendingAcks() {
        // note: may be slightly off in a running system
        // but I dont want to lock
        int pendingcnt = 0;
        List l = getChildConsumers();
        Iterator itr = l.iterator();
        while (itr.hasNext()) {
            Consumer c = (Consumer)itr.next();
            pendingcnt += c.numPendingAcks();
        }
        return pendingcnt;
    }


    public List getChildConsumers() {
        if (activeConsumers == null)
            return new ArrayList();
        return new ArrayList(activeConsumers.values());
    }

    /**
     * method to retrieve debug state of the object
     */
    public Hashtable getDebugState() {
        Hashtable ht = super.getDebugState();
        ht.put("type", "SUBSCRIPTION");
        if (durableName != null)
            ht.put("durableName", durableName);
        else
            ht.put("durableName","<none - shared non-durable>");

        ht.put("isDurable", String.valueOf(isDurable));
        ht.put("clientID", (clientID == null ? "":clientID));
        ht.put("stored", String.valueOf(stored));
        ht.put("maxNumActiveConsumers", String.valueOf(maxNumActiveConsumers));
        ht.put("valid", String.valueOf(valid));
        ht.put("activeConsumersSize", String.valueOf(activeConsumers.size()));
        Vector v = new Vector();
        synchronized(activeConsumers) {
            Iterator itr = activeConsumers.keySet().iterator();
            while (itr.hasNext()) {
                v.add( String.valueOf(((ConsumerUID)itr.next())
                           .longValue()));
            }
         }
         ht.put("activeConsumers", v);
         return ht;
    }


    /**
     * number of current active subscribers
     */
    public int getActiveSubscriberCnt() {
        return activeConsumers.size();
    }

    // only called when loading an old store
    /**
     * set the consumerUID on the object
     * @deprecated
     */
    public void setConsumerUID(ConsumerUID uid) {
        this.uid = uid;
    }

    public boolean equals(Object o) {
        if (o instanceof Subscription) {
            Subscription sub = (Subscription)o;
            if (isDurable != sub.isDurable) {
                return false;
            }
            if (isDurable) {
                return (durableName.equals(sub.durableName) &&
                        ((clientID == sub.clientID) ||
                         (clientID != null && clientID.equals(sub.clientID))));
            } else {
                return (clientID == sub.clientID ||
                       (clientID != null && clientID.equals(sub.clientID))) &&
                       (dest == sub.dest ||
                       (dest != null && dest.equals(sub.dest))) && 
                       (selstr == sub.selstr ||
                       (selstr != null && selstr.equals(sub.selstr)));
            }
        }
        return false;
    }


    /**
     * returns hashcode
     */
    public int hashCode() {
        return hashcode;
    }

    /**
     * handles transient data when class is deserialized
     */
    private void readObject(java.io.ObjectInputStream ois)
        throws IOException, ClassNotFoundException
    {
        ois.defaultReadObject();
        currentChangeRecordInfo = Collections.synchronizedMap(
                           new HashMap<Integer, ChangeRecordInfo>());
        activeConsumers = new HashMap();
        subLock = new Object();
        stored = true;
        active = false;
        getConsumerUID().setShouldStore(true);
        hashcode = calcHashcode();
        ackMsgsOnDestroy = true;  
        fi = FaultInjection.getInjection();
    }

    /**
     * method to calculate the actual hashcode
     */
    private int calcHashcode() {
        if (isDurable) {
            return durableName.hashCode() * 31+
                   (clientID == null ? 0:clientID.hashCode());
        }
        return dest.hashCode() * 31 + 
               (clientID == null ? 0 : clientID.hashCode()) +
               (31*31*(selstr == null ? 0 : selstr.hashCode()));
    } 

    /**
     * Create a Durable Subscription Object
     */
    private Subscription(DestinationUID d, String selector, 
                         boolean noLocal, String durable,
                         String clientID, boolean notify,
                         boolean autostore /* false only in testing */,
                         ConsumerUID requid)
                         throws IOException, SelectorFormatException, BrokerException {

        super(d, selector, noLocal, requid);
        logger.log(Logger.DEBUG, "Creating Subscription " +
                   uid + getDSubLogString(clientID, durableName));
        getConsumerUID().setShouldStore(true);
        this.durableName = durable;
        this.clientID = clientID;
        activeConsumers = new HashMap();
        active = false;
        hashcode = calcHashcode();
        ackMsgsOnDestroy = true;

        // First record the new subscription event with the master broker.
        if (notify) {
            Globals.getClusterBroadcast().recordCreateSubscription(this);
        } else if (Globals.getHAEnabled() && Globals.getStore().isJDBCStore()) {
            // workaorund for storing durables twice not causing
            // a store exception
           autostore = notify;
           stored = notify;
        }

        try {
            if (autostore) {
                if (fi.FAULT_INJECTION) {
                    fi.checkFaultAndThrowBrokerException(
                        FaultInjection.FAULT_STORE_DURA_1, null);
                }
                Globals.getStore().storeInterest(this, Destination.PERSIST_SYNC);
                stored = true;
            }
        } catch (Exception ex) {
            String args[] = { getDSubLogString(clientID, durable), d.toString() };
            if (ex instanceof BrokerException &&
                ((BrokerException)ex).getStatusCode() == Status.CONFLICT) { 
                logger.log(Logger.INFO, 
                    br.getKString(br.E_STORE_DURABLE, args)+": "+ex.toString(), ex);
            } else {
                logger.logStack(Logger.ERROR, br.E_STORE_DURABLE, args, ex);
                if (notify) {
                    try {
                        Globals.getClusterBroadcast().recordUnsubscribe(this);
                    } catch (Exception e) {
                        logger.logStack(Logger.ERROR, br.getKString(
                            br.X_RECORD_UNSUBSCRIBE_AFTER_FAILED_CREATION, 
                            getDSubLogString(clientID, durable), e.getMessage()), e);
                    }
                }
                if (ex instanceof BrokerException) {
                    throw (BrokerException)ex;
                }
                throw new BrokerException(ex.getMessage(), ex);
            }
        }
    }


    /**
     * Create a Non-Durable Subscription Object
     */
    private Subscription(DestinationUID d, String clientID,
        String selector, boolean noLocal)
        throws IOException, SelectorFormatException, BrokerException {

        super(d,selector,noLocal, (ConnectionUID)null);
        isDurable = false;
        logger.log(Logger.DEBUG,"Creating Non-Durable Subscription " +
             uid + " with clientID " + clientID);
        getConsumerUID().setShouldStore(true);
        this.clientID = clientID;
        activeConsumers = new HashMap();
        active = false;
        hashcode = calcHashcode();
        ackMsgsOnDestroy = true;

    }

    public void setMaxNumActiveConsumers(int cnt)
    {
        maxNumActiveConsumers = cnt;
    }

    public int getMaxNumActiveConsumers()
    {
        return maxNumActiveConsumers;
    }


    public void setShared(boolean share) {
        maxNumActiveConsumers = 
             (share ? getFirstDestination().getMaxNumSharedConsumers()
                   : 1);
    }

    public boolean getShared() {
        return maxNumActiveConsumers != 1;
    }

    public void destroyConsumer(Set s, Map remotePendings, boolean remoteCleanup,
                                boolean removeDest, boolean notify) {
        if (!valid) {
            return;
        }
        if (!isDurable) {
            Iterator itr = getDestinations().iterator();
            while (itr.hasNext()) {
                Destination d = (Destination)itr.next();
                try {
                    d.removeConsumer(uid, remotePendings, remoteCleanup, false); 
                } catch (Exception ex) {
                    logger.logStack(Logger.INFO, "Internal Error ", ex);
                }
            }
        }
        super.destroyConsumer(s, remotePendings, remoteCleanup, removeDest, notify);

        if (stored) {
            try {
                Globals.getStore().removeInterest(this, Destination.PERSIST_SYNC);
                stored = false;
            } catch (Exception ex) {
                String args[] = { getDSubLogString(clientID, durableName), 
                                  dest.toString() };
                logger.log(Logger.ERROR, br.E_REMOVE_DURABLE, args, ex);
            }
        }
    }

    /**
     * returns the durable name (if any)
     */
    public String getDurableName()
    {
        return durableName;
    }

    public void sendCreateSubscriptionNotification(Consumer consumer)
        throws BrokerException {
        Destination d = getFirstDestination();

        if ((d == null /* wildcard*/ || (! d.getIsLocal() && ! d.isInternal() && ! d.isAdmin())) &&
            Globals.getClusterBroadcast() != null) {
                Globals.getClusterBroadcast().createSubscription(this, consumer);

        }
    }

    public void attachConsumer(Consumer consumer) throws BrokerException {
        attachConsumer(consumer, null);
    }

    public void attachConsumer(Consumer consumer, Connection conn)
        throws BrokerException
    {
        logger.log(Logger.DEBUG,"Attaching Consumer " + consumer
             + " to durable " + this +" with  " + msgs.size() + " msgs "
             + getDestinationUID() + "[" + getConsumerUID() + "]");

        synchronized(subLock) {
            if (activeConsumers.get(consumer.getConsumerUID()) != null) {
                throw new ConsumerAlreadyAddedException(Globals.getBrokerResources().getKString(
                    BrokerResources.I_CONSUMER_ALREADY_ADDED, consumer.getConsumerUID(),
                    consumer.getDestinationUID()));
            }
            if (maxNumActiveConsumers == 1) {
                ConsumerUID kidc = consumer.getConsumerUID();
                uid.setConnectionUID(kidc.getConnectionUID());
                conuid = kidc.getConnectionUID();
            } else { 
                if (!activeConsumers.isEmpty() &&
                       consumer.noLocal != noLocal ) {
                    throw new IllegalStateException(
                       "nolocal must match on all consumers");
                }
            }
            if (maxNumActiveConsumers != -1 &&
                  (activeConsumers.size() >= maxNumActiveConsumers))
            {
                String args[] = {
                          getDestinations().toString(),
                          this.toString(),
                          String.valueOf(maxNumActiveConsumers),
                          String.valueOf(activeConsumers.size()) };

                throw new BrokerException(
                        Globals.getBrokerResources().getKString(
                            BrokerResources.X_TOO_MANY_SHARED,
                            args),
                        BrokerResources.X_TOO_MANY_SHARED,
                        (Throwable) null,
                        Status.CONFLICT);
            }
            boolean wasActive = isActive();
            consumer.setStoredConsumerUID(getConsumerUID());
            consumer.getConsumerUID().setShouldStore(true);
            activeConsumers.put(consumer.getConsumerUID(), 
                    consumer);
            if (msgsSubset == null) {
                msgsSubset = msgs.subSet((Filter)null);
            }

            consumer.setParentList(
                new NoPersistPartition(getStoredConsumerUID()), msgsSubset);

            consumer.setSubscription(this);

            // OK - get all matching destinations
            active = !activeConsumers.isEmpty();
            DestinationList DL = Globals.getDestinationList();
            Map<PartitionedStore, LinkedHashSet<Destination>> dmap = 
                DL.findMatchingDestinationMap(null, getDestinationUID());
            LinkedHashSet dset = null;
            for (Map.Entry<PartitionedStore, LinkedHashSet<Destination>> pair:
                 dmap.entrySet()) {
                dset = pair.getValue();
                if (dset == null) {
                    continue;
                }
                Iterator<Destination> itr1 = dset.iterator();
                while (itr1.hasNext()) {
                    Destination d = itr1.next();
                    if (d == null) {
                        continue;
                    }
                    if (isActive() && !wasActive) {
                        if (!d.isLoaded()) {
                            logger.log(Logger.DEBUG, "Loading " + d);
                            try {
                                d.load();
                            } catch (BrokerException e) {
                                logger.logStack(Logger.ERROR, e.getMessage()+" ["+pair.getKey()+"]", e);
                            }
                        }
                    }
                    d.notifyConsumerAdded(consumer, conn);
                }
            }
        }
    }

    public void releaseConsumer(ConsumerUID uid) {
        logger.log(Logger.DEBUG,"Releasing Consumer " + uid
             + " from durable " + this);

        pause("Subscription: releaseConsumer " + uid);

        Consumer consumer = null;
        synchronized (subLock) {
            consumer = (Consumer) activeConsumers.remove(uid);
            consumer.pause("Subscription: releaseConsumer B ");
            consumer.setParentList(
                new NoPersistPartition(getStoredConsumerUID()), null);
            active = !activeConsumers.isEmpty();
        }
        DestinationList DL = Globals.getDestinationList();
        List[] ll = null;
        try {
            ll = DL.findMatchingIDs(null, getDestinationUID());
        } catch (PartitionNotFoundException e) {
            ll = new List[]{ new ArrayList<DestinationUID>() }; 
        }
        List l = ll[0];
        Iterator itr = l.iterator();
        Destination[] ds = null;
        Destination d = null;
        while (itr.hasNext()) {
            ds = DL.getDestination(null, (DestinationUID)itr.next());
            d = ds[0];
            if (d != null) {
                d.notifyConsumerRemoved();
            }
        }
        if (!isDurable) {
            synchronized (Subscription.class) {
                active = !activeConsumers.isEmpty();
                if (!active) {
                    logger.log(Logger.DEBUG, "Cleaning up non-durable " +
                               " subscription " + this);
                    String cuid = clientID + ":" + 
                           consumer.getDestinationUID()   
                           + ":" + selstr;
                    nonDurableList.remove(cuid);
                    destroyConsumer(new HashSet(), (Map)null, false, true, false);

                }
            }
        }
        consumer.resume("Subscription: releaseConsumer B ");
        resume("Subscription: release consumer " + uid);
   }


    /**
     * returns the client ID (if any)
     */
    public String getClientID() {
        return clientID;
    }

    /**
     * Return a concises string representation of the object.
     * 
     * @return    a string representation of the object.
     */
    public String toString() {
        String str = "Subscription :" + uid + " - " ;
        str += " dest=" + getDestinationUID();
        if (!isDurable) {
            return str;
        }
        str += getDSubLogString(clientID, durableName);
        return str;
    }

    public void purge() throws BrokerException {

        super.purgeConsumer();
        synchronized (subLock) {
            Iterator itr = activeConsumers.values().iterator();
            while (itr.hasNext()) {
                Consumer c = (Consumer)itr.next();
                c.purgeConsumer();
            }
        }
    }

   /**********************************************************************************
    *          Static Durable Subscriber Methods
    **********************************************************************************/
    public static String getDSubKey(String cid, String dname) {
        return (cid == null ? "":cid)+":"+dname;
    }

    public static String getDSubLogString(String cid, String dname) { 
        return "["+getDSubKey(cid, dname)+"]";
    }

    private static boolean loaded= false;

    public static void clearSubscriptions() {
        nonDurableList.clear();
        cache.clear();
        durableList.clear();
        loaded = false;
    }


    public static void initSubscriptions() {

         Logger logger = Globals.getLogger();
         logger.log(Logger.DEBUG,"Initializing consumers");

         synchronized(Subscription.class) {
             if (loaded) {
                 return;
             }
             loaded = true;
         }

        // before we do anything else, make sure we dont have any
        // unexpected exceptions
        LoadException load_ex = null;
        try {
            load_ex = Globals.getStore().getLoadConsumerException();
        } catch (Exception ex) {
            // nothing to do
            logger.logStack(Logger.DEBUG,"Error loading consumer exception ", ex);
        }

        if (load_ex != null) {
            // some messages could not be loaded
            LoadException processing = load_ex;
            while (processing != null) {
                ConsumerUID cuid = (ConsumerUID)processing.getKey();
                Consumer con = (Consumer)processing.getValue();
                if (cuid == null && con == null) {
                    logger.log(Logger.WARNING,  "LoadConsumerException: "+
                          "Both key and value are corrupted");
                    continue;
                }
                if (cuid == null) { 
                    // store with valid key
                    try {
                        Globals.getStore().storeInterest(con, true);
                    } catch (Exception ex) {
                        logger.log(Logger.WARNING, 
                            BrokerResources.W_CON_RECREATE_FAILED,
                            con.getConsumerUID(), ex);
                    }
                } else {
                    // nothing we can do, remove it
                    logger.log(Logger.WARNING,
                            BrokerResources.W_CON_CORRUPT_REMOVE,
                          cuid.toString());
                    try {
                        Consumer c = new Consumer(cuid);
                        Globals.getStore().removeInterest(c, false);
                    } catch (Exception ex) {
                        logger.logStack(Logger.DEBUG,"Error removing "
                               + "corrupt consumer " + cuid, ex);
                    }

                } // end if
                processing = processing.getNextException();
            } // end while
        } 

         try {
             Consumer[] cons = Globals.getStore().getAllInterests(); 
             for (int i=0; i < cons.length; i ++) {
                 Consumer c = cons[i];
                 if (c == null) continue;
                 // at this point, we only store subscriptions
                 assert c instanceof Subscription;
                 Subscription s= (Subscription)c;
                 String clientID = s.getClientID();
                 if (clientID != null && clientID.length() == 0) {
                     clientID = null;
                 }
                 String durableName = s.getDurableName();
                 logger.log(Logger.INFO, Globals.getBrokerResources().
                     getKString(BrokerResources.I_LOAD_STORED_DURA, 
                         getDSubLogString(clientID, durableName)));
                 String key = getDSubKey(clientID, durableName);
                 if (durableList.get(key) != null) {
                     logger.log(Logger.WARNING,
                         BrokerResources.E_INTERNAL_BROKER_ERROR,
                         "The loaded durable subscription "+s+
                          getDSubLogString(clientID, durableName)+
                          " from store already exists " + durableList.get(key)+", replace");
                 }
                 durableList.put(key, s);
                 DestinationUID duid = s.getDestinationUID();

                 DestinationList DL = Globals.getDestinationList();
                 LinkedHashSet dset = null;
                 Map<PartitionedStore, LinkedHashSet<Destination>> dmap = null;
                 if (duid.isWildcard()) {
                      wildcardConsumers.add(c.getConsumerUID());
                      dmap = DL.findMatchingDestinationMap(null, duid);
                      Iterator<LinkedHashSet<Destination>> itr = dmap.values().iterator();
                      while (itr.hasNext()) {
                          dset = itr.next(); 
                          if (dset == null) {
                              continue;
                          }
                          Iterator<Destination> itr1 = dset.iterator();
                          while (itr1.hasNext()) {
                              Destination d = itr1.next();
                              if (d == null) {
                                  continue;
                              }
                              logger.log(logger.INFO, "XXXAdd durable subscription "+s+" to destination "+d);
                              d.addConsumer(s, false);
                          }
                      }
                 } else {
                 
                     Destination[] ds = DL.getDestination(null, duid.getName(), 
                                        (duid.isQueue() ? DestType.DEST_TYPE_QUEUE
                                         : DestType.DEST_TYPE_TOPIC) , true, true);
                     for (int j = 0; j < ds.length; j++) {
                         logger.log(logger.INFO, "XXXAdd durable subscription "+s+" to destination "+ds[j]);
                         if (ds[j] == null) {
                             continue;
                         }
                         ds[j].addConsumer(s, false);
                     }
                 }
             }
         } catch (Exception ex) {
             logger.logStack(Logger.ERROR,
                 BrokerResources.E_LOAD_DURABLES, ex);
         }
    }

    protected static void initDuraSubscriptions(DestinationList dl)
    throws BrokerException { 

        initSubscriptions();

        if (!dl.setDuraSubscriptionInited()) {
            return;
        }
        try {

        DestinationList DL = Globals.getDestinationList();

        Subscription s = null;
        DestinationUID duid = null;
        synchronized(Subscription.class) {
            Iterator<Subscription> itr = durableList.values().iterator();
            while (itr.hasNext()) {
                s = itr.next();
                duid = s.getDestinationUID();
                if (duid.isWildcard()) {
                    List<DestinationUID> duids = DL.findMatchingIDsByDestinationList(dl, duid);
                    Iterator itr1 = duids.iterator();
                    while (itr1.hasNext()) {
                        DestinationUID match_duid = (DestinationUID)itr1.next();
                        Destination d = DL.getDestinationByDestinationList(dl, match_duid);
                        d.addConsumer(s, false, null, false);
                        Globals.getLogger().log(Logger.INFO, 
                            "XXXI18N added durable subscription "+s+" to "+dl);
                      }

                } else {
                    Destination d = DL.getDestinationByDestinationList(
                                        dl, duid.getName(),
                                        (duid.isQueue() ? DestType.DEST_TYPE_QUEUE
                                         : DestType.DEST_TYPE_TOPIC) , true, true);
                    d.addConsumer(s, false, null, false);
                    Globals.getLogger().log(Logger.INFO, 
                        "XXXI18N added durable subscription "+s+" to "+dl);
                }
            }
        }

        } catch (Exception e) {
            Globals.getLogger().logStack(Logger.ERROR, e.getMessage(), e);  
            if (e instanceof BrokerException) {
                throw (BrokerException)e;
            }
            throw new BrokerException(e.getMessage(), e);
        }
    }

    protected static void initNonDuraSharedSubscriptions(DestinationList dl)
    throws BrokerException { 
        try {

        if (!dl.setNonDuraSharedSubscriptionInited()) {
            return;
        }

        DestinationList DL = Globals.getDestinationList();

        Subscription s = null;
        DestinationUID duid = null;
        synchronized(Subscription.class) {
            Iterator itr = nonDurableList.values().iterator();
            while (itr.hasNext()) {
                s = (Subscription)itr.next();
                duid = s.getDestinationUID();
                if (duid.isWildcard()) {
                    List<DestinationUID> duids = DL.findMatchingIDsByDestinationList(dl, duid);
                    Iterator itr1 = duids.iterator();
                    while (itr1.hasNext()) {
                        DestinationUID match_duid = (DestinationUID)itr1.next();
                        Destination d = DL.getDestinationByDestinationList(dl, match_duid);
                        d.addConsumer(s, false, null, false);
                        Globals.getLogger().log(Logger.INFO, 
                            "XXXI18N added non-durable shared subscription "+s+" to "+dl);
                      }

                } else {
                    Destination d = DL.getDestinationByDestinationList(
                                        dl, duid.getName(),
                                        (duid.isQueue() ? DestType.DEST_TYPE_QUEUE
                                         : DestType.DEST_TYPE_TOPIC) , true, true);
                    d.addConsumer(s, false, null, false);
                    Globals.getLogger().log(Logger.INFO, 
                        "XXXI18N added non-durable shared subscription "+s+" to "+dl);
                }
            }
        }
        } catch (Exception e) {
            Globals.getLogger().logStack(Logger.ERROR, e.getMessage(), e);  
            if (e instanceof BrokerException) {
                throw (BrokerException)e;
            }
            throw new BrokerException(e.getMessage(), e);
        }
    }

    public static Set getAllDurableSubscriptions(DestinationUID uid) {
        Set s = new HashSet();
        synchronized(Subscription.class) {
            Iterator<Subscription> itr = durableList.values().iterator();
            while (itr.hasNext()) {
                Subscription sub = itr.next();
                if (uid == null || uid.equals(sub.getDestinationUID())) {
                    s.add(sub);
                }
            }
        }
        return s;
    }
    public static Set getAllNonDurableSubscriptions(DestinationUID uid)
    {
        Set s = new HashSet();
        synchronized(Subscription.class) {
            Iterator itr = nonDurableList.values().iterator();
            while (itr.hasNext()) {
                Subscription sub = (Subscription)itr.next();
                if (uid == null || uid.equals(sub.getDestinationUID())) {
                    s.add(sub);
                }
            }
        }
        return s;
    }
    public static Set getAllSubscriptions(DestinationUID uid)
    {
        Set s = new HashSet();
        synchronized(Subscription.class) {
            Iterator<Subscription> itr = durableList.values().iterator();
            while (itr.hasNext()) {
                Subscription sub = itr.next();
                if (uid == null || uid.equals(sub.getDestinationUID())) {
                    s.add(sub);
                }
            }
            itr = nonDurableList.values().iterator();
            while (itr.hasNext()) {
                Subscription sub = (Subscription)itr.next();
                if (uid == null || uid.equals(sub.getDestinationUID())) {
                    s.add(sub);
                }
            }
        }
        return s;
    }


    /**
     * This method is invoked when the unsubscribe() happens
     * elsewhere on the cluster.
     */
    public static Subscription remoteUnsubscribe(
        String durableName, String clientID)
        throws BrokerException {
         return unsubscribe(durableName, clientID, false, 
                            false, false, false);
    }

    public static Subscription unsubscribe(
        String durableName, String clientID)
        throws BrokerException {
         return unsubscribe(durableName, clientID, false, false);
    }

    public static Subscription unsubscribeOnDestroy(
        String durableName, String clientID, boolean notify)
        throws BrokerException {
         return unsubscribe(durableName, clientID, false, true, notify,
               true /* record removal */);
    }

    public static Subscription unsubscribe(
        String durableName, String clientID, boolean override) 
        throws BrokerException {
       return unsubscribe(durableName, clientID, override, false); 
    }

    private static Subscription  unsubscribe(String durableName, 
        String clientID, boolean override, boolean removingDest)
        throws BrokerException {
        return unsubscribe(durableName, clientID, override,
            removingDest, true, false /* dont record removal */);
    }

    private static Subscription  unsubscribe(String durableName, 
        String clientID, boolean override, boolean removingDest,
        boolean notify, boolean recordRemoval)
        throws BrokerException {
        synchronized(Subscription.class) {
            Object key = getDSubKey(clientID, durableName);
            Subscription s = durableList.get(key);
            if (s == null) {
                   DestinationUID did = (DestinationUID)cache.get(key);
                   if (did != null) {
                       // unsubscribing consumer which was removed when
                       // the destination was destroyed
                       String args[]={ getDSubLogString(clientID, durableName), 
                                       did.toString() };
                       throw new BrokerException(
                            Globals.getBrokerResources().getKString(
                                BrokerResources.X_DEST_FOR_DURABLE_REMOVED, args),
                            BrokerResources.X_DEST_FOR_DURABLE_REMOVED,
                            (Throwable) null,
                            Status.PRECONDITION_FAILED);
                   }
                   throw new BrokerException(
                        Globals.getBrokerResources().getKString(
                            BrokerResources.X_UNKNOWN_DURABLE_INTEREST, 
                            getDSubLogString(clientID, durableName)),
                        BrokerResources.X_UNKNOWN_DURABLE_INTEREST,
                        (Throwable) null,
                        Status.NOT_FOUND);
            }
            if (s.isActive()) {
               if (override || removingDest) {
                   Iterator itr = null;
                   synchronized (s.subLock) {
                      itr = new HashSet(s.activeConsumers.values()).iterator();
                   }
                   while (itr.hasNext()) {
                       Consumer c = (Consumer) itr.next();
                       c.destroyConsumer(new HashSet(), (Map)null, false, removingDest, notify);
                   }
                   s.activeConsumers.clear();
               } else {
                   String args[] = { getDSubLogString(clientID, durableName), 
                                     s.getDestinationUID().getName() };
                   throw new BrokerException(
                        Globals.getBrokerResources().getKString(
                            BrokerResources.X_NON_EMPTY_DURABLE, args),
                        BrokerResources.X_NON_EMPTY_DURABLE,
                        (Throwable) null,
                        Status.CONFLICT);
                }
            }

            if (recordRemoval) {
                cache.put(getDSubKey(clientID, durableName),
                          s.getDestinationUID());
            }

            // First record the unsubscribe event with master broker.
            if (notify) {
                Globals.getClusterBroadcast().recordUnsubscribe(s);
            }

            durableList.remove(key);
            s.destroyConsumer(new HashSet(), (Map)null, false, removingDest, notify);
            return s;
        }
    }

    public static Subscription subscribe(String name,
        String clientID, String sel, DestinationUID duid,
        boolean nolocal, boolean notify,
        boolean autostore /* false only in testing */,
        ConsumerUID requid)
        throws BrokerException, SelectorFormatException {

        synchronized(Subscription.class) {
             Subscription s = findDurableSubscription(clientID, name);
             if (s != null) {
                String args[] = { getDSubLogString(clientID, name), duid.toString() };
                throw new ConflictException(
                   Globals.getBrokerResources().getKString(
                        BrokerResources.X_DURABLE_CONFLICT, args));
             }
             try {
                 s = new Subscription(duid, sel,nolocal,
                     name, clientID, notify, autostore, requid); 
             } catch (IOException ex) {

                 String args[] = { getDSubLogString(clientID, name), duid.toString()};
                 throw new BrokerException(
                      Globals.getBrokerResources().getKString(
                          BrokerResources.E_CREATE_DURABLE, args), ex);
             }
             durableList.put(getDSubKey(clientID, name), s);

             return s;
            
        }
    }

    public static Subscription findDurableSubscription(
        String clientID, String durableName) {
        
        synchronized(Subscription.class) {
            assert durableName != null;
            return (Subscription)durableList.get(
                    getDSubKey(clientID, durableName));
        }
    }

    public static Subscription findDurableSubscription(String key) {
        
        synchronized(Subscription.class) {
            assert key != null;
            return (Subscription)durableList.get(key);
        }
    }

    public static Subscription findCreateDurableSubscription(
        String clientID, String durableName, DestinationUID uid,
        String selectorstr, boolean noLocal) 
        throws BrokerException, SelectorFormatException { 

        return findCreateDurableSubscription(clientID,
           durableName, uid, selectorstr,
           noLocal, false, null);
    }

    public static Subscription findCreateDurableSubscription(
        String clientID, String durableName, DestinationUID uid, 
        String selectorstr, boolean noLocal, boolean notify) 
        throws BrokerException, SelectorFormatException { 

        return findCreateDurableSubscription(clientID,
               durableName, uid, selectorstr,
               noLocal, true, null);
    }

    public static Subscription findCreateDurableSubscription(
        String clientID, String durableName, DestinationUID uid,
        String selectorstr, boolean noLocal, boolean notify, ConsumerUID requid) 
        throws BrokerException, SelectorFormatException { 

        Logger logger = Globals.getLogger();
        synchronized(Subscription.class) {
             Subscription s = findDurableSubscription(clientID, durableName);
             
             if (s != null && ((s.isActive() && !s.getShared()) || 
                               !uid.equals(s.getDestinationUID()) || 
                               s.getNoLocal() != noLocal || 
                               ((selectorstr != null || s.getSelectorStr() != null) && 
                                (selectorstr == null ||
                                !selectorstr.equals(s.getSelectorStr()))))) {
                   // dont match
                   if (s.isActive() && !s.getShared()) {

                       String args[] = { getDSubLogString(clientID, durableName), uid.toString() };
                       throw new BrokerException(
                            Globals.getBrokerResources().getKString(
                                BrokerResources.X_DURABLE_CONFLICT, args),
                            BrokerResources.X_DURABLE_CONFLICT,
                            (Throwable) null,
                            Status.CONFLICT);
                   } else {
                       logger.log(Logger.DEBUG, "Unsubscribing subscription "+
                                  getDSubLogString(clientID, durableName));
                       unsubscribe(durableName, clientID, false, false, notify, false);
                       s = null;
                   }
             }
             if (s == null) {
                  logger.log(Logger.DEBUG,"creating new durable subscription "+
                             getDSubLogString(clientID, durableName)+" got " + s);

                  s = subscribe(durableName, clientID, selectorstr, uid, 
                                noLocal, notify, true, requid);
             }
             return s;
        }
    }
/**********************************************************************************
 *          Static Non-Durable Subscriber Methods
 **********************************************************************************/

    private static String createNDUID( String clientID,
             DestinationUID uid, String selectorstr) {
            return clientID + ":" + uid + ":" + selectorstr;
    }

    public static Subscription findNonDurableSubscription(String clientID,
             DestinationUID uid, String selectorstr) {
        
        synchronized(Subscription.class) {
            String lookup = createNDUID(clientID, uid, selectorstr);
            // OK .. look the subscription up
            return (Subscription)nonDurableList.get(
                    lookup);
        }
    }

    /**
     * create a non-durable shared subscription
     * @return the new subscription (if just created)
     */
    public static Subscription createAttachNonDurableSub(Consumer c, Connection con)
        throws BrokerException, IOException, SelectorFormatException
    {
        String clientID = null;
        if (con != null) {
            clientID = (String)con.getClientData(IMQConnection.CLIENT_ID);
        }
        synchronized (Subscription.class) {
            Subscription sub = findNonDurableSubscription(
                clientID, c.getDestinationUID(),
                c.getSelectorStr());
            if (sub == null) {
                sub = findCreateNonDurableSubscription(clientID,
                    c.getSelectorStr(),
                    c.getDestinationUID(),
                    c.getNoLocal(), null);
            }
            sub.attachConsumer(c, con);
            return sub;
        }
    }

    
    public static Subscription findCreateNonDurableSubscription(
            String clientID, String selectorstr,
            DestinationUID duid, boolean isNoLocal, ConsumerUID optUID) 
        throws BrokerException, IOException, SelectorFormatException
    {
        synchronized (Subscription.class) {
            
            // OK .. look the subscription up
            String uid = createNDUID(clientID, duid, selectorstr);
            // OK .. look the subscription up
            Subscription sub = (Subscription)nonDurableList.get(uid);
            if (sub == null) {
                sub = new Subscription(duid, clientID, selectorstr, isNoLocal);
                if (optUID != null) {
                   sub.setConsumerUID(optUID);
                }
                Globals.getLogger().log(Logger.DEBUG,"Created new non-durable "
                    + "subscription " + sub);
                nonDurableList.put(uid, sub);
            }  
            return sub;
        }

    }
}
