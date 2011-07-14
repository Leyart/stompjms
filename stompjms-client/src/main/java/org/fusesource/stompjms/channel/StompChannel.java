/**
 * Copyright (C) 2010-2011, FuseSource Corp.  All rights reserved.
 *
 *     http://fusesource.com
 *
 * The software in this package is published under the terms of the
 * CDDL license a copy of which has been included with this distribution
 * in the license.txt file.
 */

package org.fusesource.stompjms.channel;

import org.fusesource.hawtbuf.AsciiBuffer;
import org.fusesource.hawtbuf.Buffer;
import org.fusesource.hawtbuf.ByteArrayOutputStream;
import org.fusesource.stompjms.StompJmsDestination;
import org.fusesource.stompjms.StompJmsExceptionSupport;
import org.fusesource.stompjms.StompJmsMessageListener;
import org.fusesource.stompjms.message.StompJmsMessage;
import org.fusesource.stompjms.util.LRUCache;
import org.fusesource.stompjms.util.SendRequest;

import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.net.SocketFactory;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.text.BreakIterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.fusesource.stompjms.channel.Stomp.*;

public class StompChannel implements StompFrameListener {
    static final long TIMEOUT = -1;
    String channelId;
    String userName;
    String password;
    String ackMode;
    URI brokerURI;
    URI localURI;
    StompSocket socket;
    StompJmsMessageListener listener;
    ExceptionListener exceptionListener;
    AtomicBoolean started = new AtomicBoolean();
    AtomicBoolean initialized = new AtomicBoolean();
    AtomicBoolean connected = new AtomicBoolean();
    Map<AsciiBuffer, SendRequest> requests = new LRUCache<AsciiBuffer, SendRequest>();
    AsciiBuffer currentTransactionId = null;
    AsciiBuffer session;
    
    private AtomicLong requestCounter = new AtomicLong();
    
    public AsciiBuffer getSession() {
        return session;
    }
    public AsciiBuffer nextId() {
        return nextId("");
    }

    public StompSocket getSocket() {
        return socket;
    }

    public AsciiBuffer nextId(String prefix) {
        return new AsciiBuffer(prefix+Long.toString(requestCounter.incrementAndGet()));
    }

    public StompChannel copy() {
        StompChannel copy = new StompChannel();
        copy.brokerURI = this.brokerURI;
        copy.localURI = this.localURI;
        copy.userName = this.userName;
        copy.password = this.password;
        copy.ackMode = this.ackMode;
        return copy;
    }

    public void initialize() throws JMSException {
        if (this.initialized.compareAndSet(false, true)) {
            try {
                this.socket = new StompSocket(SocketFactory.getDefault(), localURI, brokerURI);
                this.socket.setStompFrameListener(this);
                this.socket.initialize();
            } catch (IOException e) {
                throw StompJmsExceptionSupport.create(e);
            }
        }
    }


    public void connect() throws JMSException {
        if (this.connected.compareAndSet(false, true)) {
            initialize();
            try {
                StompFrame connected = this.socket.connect(getUserName(), getPassword(), getChannelId());
                session = connected.headers.get(SESSION);
                if ( session==null ) {
                    session = new AsciiBuffer("id-"+UUID.randomUUID().toString());
                }
            } catch (IOException e) {
                throw StompJmsExceptionSupport.create(e);
            }
        }
    }

    public void start() throws JMSException {
        if (started.compareAndSet(false, true)) {
            connect();
            try {
                this.socket.setStompFrameListener(this);
                this.socket.start();
            } catch (Throwable e) {
                this.started.set(false);
                throw StompJmsExceptionSupport.create(e);
            }
        }
    }

    public boolean isStarted() {
        return started.get();
    }

    public void stop() throws JMSException {
        if (started.compareAndSet(true, false)) {
            if (this.socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    throw StompJmsExceptionSupport.create(e);
                }
            }
        }
    }

    public void sendMessage(StompJmsMessage message, boolean sync) throws JMSException {
        StompJmsMessage copy = message.copy();
        copy.onSend();
        StompFrame frame = copy.getFrame();
        frame.setAction(SEND);
        frame.headers.put(CONTENT_LENGTH, new AsciiBuffer(Integer.toString(frame.content.length)));
        addTransaction(frame);
        try {
            if( sync ) {
                sendRequest(frame);
            } else {
                sendFrame(frame);
            }
        } catch (IOException e) {
            throw StompJmsExceptionSupport.create(e);
        }
    }

    public void ackMessage(AsciiBuffer consumerId, AsciiBuffer messageId, Boolean sync) throws JMSException {
//        System.out.println(""+socket.getLocalAddress() +" ack "+ messageId);
        StompFrame frame = new StompFrame();
        frame.setAction(ACK);
        frame.headers.put(SUBSCRIPTION, consumerId);
        frame.headers.put(MESSAGE_ID, messageId);
        addTransaction(frame);
        try {
            if(sync) {
                sendRequest(frame);
            } else {
                sendFrame(frame);
            }
        } catch (IOException e) {
            throw StompJmsExceptionSupport.create(e);
        }
    }

    public void subscribe(StompJmsDestination destination, AsciiBuffer consumerId, AsciiBuffer selector, boolean clientAck, boolean persistent, boolean browser) throws JMSException {
        StompFrame frame = new StompFrame();
        frame.setAction(SUBSCRIBE);
        frame.headers.put(DESTINATION, destination.toBuffer());
        frame.headers.put(ID, consumerId);
        if (selector != null && selector.trim().isEmpty() == false) {
            frame.headers.put(SELECTOR, selector);
        }
//        if (clientAck) {
//            frame.headers.put(ACK_MODE, CLIENT);
//        } else {
//            frame.headers.put(ACK_MODE, AUTO);
//        }
        frame.headers.put(ACK_MODE, CLIENT);
        if (persistent) {
            frame.headers.put(PERSISTENT, TRUE);
        }
        if (browser) {
            frame.headers.put(BROWSER, TRUE);
        }
        try {
            sendRequest(frame);
        } catch (IOException e) {
            throw StompJmsExceptionSupport.create(e);
        }
    }

    public void unsubscribe(StompJmsDestination destination, AsciiBuffer consumerId, boolean persistent, boolean browser) throws JMSException {
        StompFrame frame = new StompFrame();
        frame.setAction(UNSUBSCRIBE);
        if (destination != null) {
            frame.headers.put(DESTINATION, destination.toBuffer());
        }
        frame.headers.put(ID, consumerId);
        if (persistent) {
            frame.headers.put(PERSISTENT, TRUE);
        }
        try {
            sendFrame(frame);
        } catch (IOException e) {
            throw StompJmsExceptionSupport.create(e);
        }
    }


    public synchronized AsciiBuffer startTransaction() throws JMSException {
        if (this.currentTransactionId != null) {
            throw new JMSException("Transaction " + this.currentTransactionId + " already in progress");
        }
        this.currentTransactionId = nextId("TX-");
        StompFrame frame = new StompFrame();
        frame.setAction(BEGIN);
        addTransaction(frame);
        try {
            sendFrame(frame);
        } catch (IOException e) {
            throw StompJmsExceptionSupport.create(e);
        }
        return currentTransactionId;
    }

    public synchronized void commitTransaction() throws JMSException {
        AsciiBuffer id = this.currentTransactionId;
        StompFrame frame = new StompFrame();
        frame.setAction(COMMIT);
        addTransaction(frame);
        this.currentTransactionId = null;
        try {
            sendRequest(frame);
        } catch (IOException e) {
            throw StompJmsExceptionSupport.create(e);
        }
    }

    public void rollbackTransaction() throws JMSException {
        AsciiBuffer id = this.currentTransactionId;
        StompFrame frame = new StompFrame();
        frame.setAction(ABORT);
        addTransaction(frame);
        this.currentTransactionId = null;
        try {
            sendRequest(frame);
        } catch (IOException e) {
            throw StompJmsExceptionSupport.create(e);
        }
    }

    public void sendFrame(StompFrame frame) throws IOException {
        this.socket.sendFrame(frame);
    }

    public void sendRequest(StompFrame frame) throws IOException {
        AsciiBuffer id = nextId();
        SendRequest sr = new SendRequest();
        synchronized (this.requests) {
            this.requests.put(id, sr);
        }
        frame.headers.put(RECEIPT_REQUESTED, id);
        this.socket.sendFrame(frame);
        try {
            StompFrame response = sr.get(TIMEOUT);
            if (response == null) {
                throw new IOException("SendRequest timed out for " + frame);
            }
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }

    public void onFrame(StompFrame frame) {
        AsciiBuffer action = frame.getAction();
        if (frame.getClass() == StompFrameError.class) {
            handleException(((StompFrameError) frame).getException());
        }
        if (action.startsWith(MESSAGE)) {
            try {
//                if( frame.headers.get(DESTINATION).equals(AsciiBuffer.ascii("/queue/DC_IncomingOffersQ1"))) {
//                    socket.trace = true;
//                }
//                if(socket.trace) {
//                    System.out.println("processing a message");
//                }
                StompJmsMessage msg = StompTranslator.convert(frame);
                msg.setReadOnlyBody(true);
                msg.setReadOnlyProperties(true);
                StompJmsMessageListener l = this.listener;
                if (l != null) {
                    l.onMessage(msg);
                }
//                if(socket.trace) {
//                    System.out.println("done processing a message");
//                }
            } catch (JMSException e) {
                handleException(e);
            }
        } else if (action.startsWith(RECEIPT)) {
//            if(socket.trace) {
//                System.out.println("got the receipt");
//            }
            AsciiBuffer id = frame.headers.get(RECEIPT_ID);
            if (id != null) {
                synchronized (this.requests) {
                    SendRequest request = this.requests.remove(id);
                    if (request != null) {
                        request.put(id, frame);
                    } else {
                        handleException(new ProtocolException("Stomp Response without a valid receipt id: " + id + " for Frame " + frame));
                    }
                }
            } else {
                handleException(new ProtocolException("Stomp Response with no receipt id: " + frame));
            }
        } else if (action.startsWith(ERROR)) {
            handleException(new ProtocolException("Received an error: " + errorMessage(frame)));
        } else {
            handleException(new ProtocolException("Unknown STOMP action: " + action));
        }


    }

    public static String errorMessage(StompFrame frame) {
        AsciiBuffer value = frame.headers.get(MESSAGE_HEADER);
        if( value!=null ) {
            return decodeHeader(value);
        } else {
            return frame.getBody();
        }
    }


    public static String decodeHeader(Buffer value) {
        if( value ==null )
            return null;

        ByteArrayOutputStream rc = new ByteArrayOutputStream(value.length);
        Buffer pos = new Buffer(value);
        int max = value.offset + value.length;
        while (pos.offset < max) {
            if (pos.startsWith(ESCAPE_ESCAPE_SEQ)) {
                rc.write(ESCAPE_BYTE);
                pos.offset += 2;
            } else if (pos.startsWith(COLON_ESCAPE_SEQ)) {
                rc.write(COLON_BYTE);
                pos.offset += 2;
            } else if (pos.startsWith(NEWLINE_ESCAPE_SEQ)) {
                rc.write(NEWLINE_BYTE);
                pos.offset += 2;
            } else {
                rc.write(pos.data[pos.offset]);
                pos.offset += 1;
            }
        }
        try {
            return new String(rc.toByteArray(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e); // not expected.
        }
    }

    public static AsciiBuffer encodeHeader(String value) {
        if( value ==null )
            return null;
        try {
            byte[] data = value.getBytes("UTF-8");
            ByteArrayOutputStream rc = new ByteArrayOutputStream(data.length);
            for (byte d : data) {
                switch (d) {
                    case ESCAPE_BYTE:
                        rc.write(ESCAPE_ESCAPE_SEQ);
                        break;
                    case COLON_BYTE:
                        rc.write(COLON_ESCAPE_SEQ);
                        break;
                    case NEWLINE_BYTE:
                        rc.write(COLON_ESCAPE_SEQ);
                        break;
                    default:
                        rc.write(d);
                }
            }
            return rc.toBuffer().ascii();
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e); // not expected.
        }
    }


    /**
     * @return the channelId
     */
    public String getChannelId() {
        return this.channelId;
    }

    /**
     * @param channelId the channelId to set
     */
    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    /**
     * @return the userName
     */
    public String getUserName() {
        return this.userName;
    }

    /**
     * @param userName the userName to set
     */
    public void setUserName(String userName) {
        this.userName = userName;
    }

    /**
     * @return the password
     */
    public String getPassword() {
        return this.password;
    }

    /**
     * @param password the password to set
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * @return the ackMode
     */
    public String getAckMode() {
        return this.ackMode;
    }

    /**
     * @param ackMode the ackMode to set
     */
    public void setAckMode(String ackMode) {
        this.ackMode = ackMode;
    }

    /**
     * @return the brokerURI
     */
    public URI getBrokerURI() {
        return this.brokerURI;
    }

    /**
     * @param brokerURI the brokerURI to set
     */
    public void setBrokerURI(URI brokerURI) {
        this.brokerURI = brokerURI;
    }

    /**
     * @return the localURI
     */
    public URI getLocalURI() {
        return this.localURI;
    }

    /**
     * @param localURI the localURI to set
     */
    public void setLocalURI(URI localURI) {
        this.localURI = localURI;
    }

    /**
     * @return the listener
     */
    public StompJmsMessageListener getListener() {
        return this.listener;
    }

    /**
     * @param listener the listener to set
     */
    public void setListener(StompJmsMessageListener listener) {
        this.listener = listener;
    }

    public void setExceptionListener(ExceptionListener listener) {
        this.exceptionListener = listener;
    }

    private void handleException(Exception e) {
        ExceptionListener l = this.exceptionListener;
        if (l != null) {
            l.onException(StompJmsExceptionSupport.create(e));
        } else {
            e.printStackTrace();
        }
    }

    private synchronized void addTransaction(StompJmsMessage message) {
        if (this.currentTransactionId != null) {
            message.setTransactionId(this.currentTransactionId);
        }
    }

    private synchronized void addTransaction(StompFrame frame) {
        if (this.currentTransactionId != null) {
            frame.headers.put(TRANSACTION, this.currentTransactionId);
        }
    }

}
