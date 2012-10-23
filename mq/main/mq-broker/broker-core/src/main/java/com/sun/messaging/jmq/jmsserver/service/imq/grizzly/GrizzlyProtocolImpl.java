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
 */ 

package com.sun.messaging.jmq.jmsserver.service.imq.grizzly;

import java.util.Map;
import java.util.HashMap;
import java.io.IOException;
import java.nio.channels.spi.AbstractSelectableChannel;
import com.sun.messaging.jmq.util.log.Logger;
import com.sun.messaging.jmq.jmsservice.BrokerEvent;
import com.sun.messaging.jmq.jmsserver.Broker;
import com.sun.messaging.jmq.jmsserver.license.LicenseBase;
import com.sun.messaging.jmq.jmsserver.util.BrokerException;
import com.sun.messaging.jmq.jmsserver.net.Protocol;
import com.sun.messaging.jmq.jmsserver.net.ProtocolStreams;
import com.sun.messaging.jmq.jmsserver.net.ProtocolCallback;
import com.sun.messaging.jmq.jmsserver.net.tcp.TcpProtocol;
import com.sun.messaging.jmq.jmsserver.Globals;
import com.sun.messaging.jmq.jmsserver.resources.*;

public class GrizzlyProtocolImpl implements Protocol
{

    private static final int defaultReadTimeout = TcpProtocol.defaultReadTimeout;
    private static final int defaultLingerTimeout = TcpProtocol.defaultLingerTimeout;
    private static final int defaultBacklog = TcpProtocol.defaultBacklog;
    private static final int defaultPort = TcpProtocol.defaultPort;

    private static Boolean TLS_ALLOWED = null;
    private boolean requireClientAuth = false;

    private GrizzlyIPService service = null;
    private String proto = null;
    private int readTimeout = defaultReadTimeout;
    private int lingerTimeout = defaultLingerTimeout;
    private int backlog = defaultBacklog;
    private boolean tcpNoDelay = true;

    private int inputBufferSize = 0;
    private int outputBufferSize = 0;

    protected int port = defaultPort;
    protected String hostname = null; // all hosts

    private int minThreads = 4; 
    private int maxThreads = 10; 

    public GrizzlyProtocolImpl(GrizzlyIPService s, String proto) {
        this.service = s;
        this.proto = proto;
        if (proto.equals("tls")) {
            synchronized(GrizzlyProtocolImpl.class) {
                if (TLS_ALLOWED == null) {  
                    try {
                        LicenseBase license = Globals.getCurrentLicense(null);
                        TLS_ALLOWED = license.getBooleanProperty(
                                        license.PROP_ENABLE_SSL, false);
                    } catch (BrokerException ex) {
                        TLS_ALLOWED = Boolean.FALSE;
                    }
                }
            }
            if (!TLS_ALLOWED.booleanValue()) {
                String emsg = Globals.getBrokerResources().getKString(
                                BrokerResources.E_FATAL_FEATURE_UNAVAILABLE,
                                Globals.getBrokerResources().getString(BrokerResources.M_SSL_JMS));
                Globals.getLogger().log(Logger.ERROR, emsg);
                Broker.getBroker().exit(1, emsg, BrokerEvent.Type.FATAL_ERROR);
            }
        }
    }

    protected String getType() {
        return proto;
    }

    public void setNoDelay(boolean v) {
        tcpNoDelay = v;
    }

    protected boolean getNoDelay() {
        return tcpNoDelay;
    }

    protected int getLingerTimeout() {
        return lingerTimeout;
    }

    public void setTimeout(int time) {
        readTimeout = time; 
    }

    protected int getTimeout() {
        return readTimeout;
    }

    protected int getBacklog() {
        return backlog;
    }

    public void setInputBufferSize(int size) {
        inputBufferSize = size;
    }

    public void setOutputBufferSize(int size) {
        outputBufferSize = size;
    }

    public int getInputBufferSize() {
        throw new UnsupportedOperationException(
        "Unsupported call: "+getClass().getName()+".getInputBufferSize");
    }

    public int getOutputBufferSize() {
        throw new UnsupportedOperationException(
        "Unsupported call: "+getClass().getName()+".getOutputBufferSize");
    }

    public boolean getBlocking() {
        throw new UnsupportedOperationException(
        "Unsupported call: "+getClass().getName()+".getBlocking");
    }

    protected boolean getRequireClientAuth() {
        return requireClientAuth;
    }

    public void registerProtocolCallback(ProtocolCallback cb, Object callback_data) {
        throw new RuntimeException(
        "Unsupported call: "+getClass().getName()+".registerProtocolCallback()");
    }

    protected void notifyProtocolCallback() {
        throw new RuntimeException(
        "Unsupported call: "+getClass().getName()+".notifyProtocolCallback()");
    }

    public boolean canPause() {
        throw new RuntimeException(
        "Unsupported call: "+getClass().getName()+".canPause()");
    }

    public void configureBlocking(boolean blocking) 
    throws UnsupportedOperationException, IOException {
        throw new UnsupportedOperationException(
        "Unsupported call: "+getClass().getName()+".configureBlocking");
    }

    public AbstractSelectableChannel getChannel()
    throws IOException {
        return null;
    }

    public ProtocolStreams accept() throws IOException {
        throw new UnsupportedOperationException("GrizzlyProtocolImpl:accept");
    }

    public void open() throws IOException, IllegalStateException {
    }

    public boolean isOpen() {
        return service.isOpen();
    }

    public void close() throws IOException, IllegalStateException {
    }

    public void checkParameters(Map params)
    throws IllegalArgumentException {
        TcpProtocol.checkTcpParameters(params);
    }

    /**
     * @return old params if param change cause rebind
     */
    public Map setParameters(Map params) throws IOException {
        HashMap oldparams =  null;

        int newport = TcpProtocol.getIntValue("port", params, port);
        readTimeout = TcpProtocol.getIntValue("readtimeout", params, readTimeout);
        lingerTimeout = TcpProtocol.getIntValue("solinger", params, lingerTimeout);
        int newbacklog = TcpProtocol.getIntValue("backlog", params, backlog);
        String newhostname = (String) params.get("hostname");
        if (newhostname == null) {
            newhostname =  Globals.getHostname();
        }
        if (newhostname == null ||  newhostname.trim().length() == 0) {
            newhostname = Globals.HOSTNAME_ALL;
        }
	    int oldport = port;
	    int oldbacklog = backlog;

        boolean newhost = (newhostname == null && hostname != null) ||
                   (newhostname != null && hostname == null) ||
                   (newhostname != null && hostname != null &&
                         ! newhostname.equals(hostname));

        if (newport != port || newbacklog != backlog || newhost) {
            oldparams = new HashMap();
            if (newport != -1) {
                oldparams.put("port", String.valueOf(port));
                port = newport;
            }
            if (newbacklog != -1) {
                oldparams.put("backlog", String.valueOf(backlog));
                backlog = newbacklog;
            }
            if (newhost) {
                oldparams.put("hostname", (hostname == null ? "":hostname));
                hostname = newhostname;
            }
        }
        if (proto.equals("tls")) {
            requireClientAuth = TcpProtocol.getBooleanValue("requireClientAuth",
                                    params, requireClientAuth);
        }
        return oldparams;
    }

    public int getLocalPort() {
        return service.getLocalPort();
    }

    public String getHostName() {
        if (hostname == null || hostname.equals("") || 
            hostname.equals(Globals.HOSTNAME_ALL)) {
            return null;
        }
        return hostname;
    }

    public int getPort() {
        return port;
    }

    protected void setMinMaxThreads(int min, int max, String svcname) 
    throws IllegalArgumentException {
        int tmpmin = min/2;
        int tmpmax = (max == 1 ? 1:max/2);
        if (tmpmin <= -1) {
            tmpmin = minThreads;
        }
	if (tmpmax <= -1) {
            tmpmax = maxThreads;
        }

        if (tmpmax == 0) {
            throw new IllegalArgumentException(
                Globals.getBrokerResources().getKString(
                    BrokerResources.X_MAX_THREAD_ILLEGAL_VALUE,
                    svcname, String.valueOf(max)));
        }
	if (tmpmin > tmpmax) {
            throw new IllegalArgumentException(
		  Globals.getBrokerResources().getKString(
		  BrokerResources.X_THREADPOOL_MIN_GT_MAX,
		  String.valueOf(min), String.valueOf(max)));
	}
        minThreads = tmpmin;
        maxThreads = tmpmax;
    }

    protected int getMinThreads() {
        return minThreads;
    }

    protected int getMaxThreads() {
        return maxThreads;
    }

    public String toString() {
        return getType()+"(host = "+(hostname == null ? Globals.HOSTNAME_ALL  : hostname)
                 + ", port="+port+ ", mode=grizzly)";
    }
}
