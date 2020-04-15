package com.global.api.terminals.ingenico.interfaces;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.global.api.entities.exceptions.ApiException;
import com.global.api.entities.exceptions.ConfigurationException;
import com.global.api.entities.exceptions.MessageException;
import com.global.api.terminals.TerminalUtilities;
import com.global.api.terminals.abstractions.IDeviceCommInterface;
import com.global.api.terminals.abstractions.IDeviceMessage;
import com.global.api.terminals.abstractions.ITerminalConfiguration;
import com.global.api.terminals.ingenico.responses.BroadcastMessage;
import com.global.api.terminals.ingenico.variables.INGENICO_GLOBALS;
import com.global.api.terminals.messaging.IBroadcastMessageInterface;
import com.global.api.terminals.messaging.IMessageReceivedInterface;
import com.global.api.terminals.messaging.IMessageSentInterface;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Array;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

interface TimeoutBlock{
    public void setTimeoutException(Exception e, int tag);
}

public class IngenicoTcpInterface implements IDeviceCommInterface {
    private ServerSocket serverSocket;
    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;

    private Thread serverThread;
    private int port;
    private int timeout;

    private ITerminalConfiguration settings;
    private BroadcastMessage broadcastMessage;

    private IBroadcastMessageInterface onBroadcastMessage;
    private IMessageSentInterface onMessageSent;
    private IMessageReceivedInterface onMessageReceived;

    private byte[] terminalResponse;

    private boolean serverTimedOut = false;

    private TimeoutBlock timeoutSendBlock;

    String errorTag = "";

    private final String TAG = "IngenicoTcpInterface";

    public IngenicoTcpInterface(ITerminalConfiguration settings)
            throws ConfigurationException, IOException, MessageException {
        this.settings = settings;

        // Start Server Socket
        connect();
    }

    public void setTimeoutSendBlock(TimeoutBlock block){
        timeoutSendBlock = block;
    }

    @Override
    public void connect() throws ConfigurationException {
        if (!settings.getPort().isEmpty()) {
            port = Integer.parseInt(settings.getPort());
            timeout = settings.getTimeout();

            if (serverThread != null){
                serverThread.interrupt();
                serverThread = null;
                disconnect();
            }

            serverThread = new Thread(new ConnectTask());
            serverThread.start();

        } else {
            throw new ConfigurationException("Port is missing");
        }
    }

    @Override
    public void disconnect() {
        try {
            if (serverSocket != null){
                if (!serverSocket.isClosed()) serverSocket.close();
            }

            if (socket != null){
                if (socket.isConnected()) {
                    socket.shutdownInput();
                    socket.shutdownOutput();
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void triggerSendBlock(Exception e, int tag){

        disconnect();

        if (timeoutSendBlock != null) {
            timeoutSendBlock.setTimeoutException(e, tag);
        }

        e.printStackTrace();
    }

    @Override
    public byte[] send(IDeviceMessage message) throws ApiException {
        terminalResponse = null;

        final byte[] buffer = message.getSendBuffer();

        setTimeoutSendBlock(new TimeoutBlock() {
            @Override
            public void setTimeoutException(Exception e, int tag){
                timeoutSendBlock = null;
                serverTimedOut = true;
                errorTag = String.valueOf(tag);
            }
        });

        try {
            if (serverSocket == null) {
                throw new ConfigurationException("Error: Server is not running.");
            }

            output.write(buffer, 0, buffer.length);
            output.flush();

            onMessageSent.messageSent(TerminalUtilities.getString(buffer).substring(2));

            while (terminalResponse == null) {
                Thread.sleep(10);
                if (serverTimedOut){
                    serverTimedOut = false;
                    if (errorTag == "7"){
                        throw new ConfigurationException("Error: Terminal disconnected");
                    } else {
                        throw new ConfigurationException("Error: Transaction timed-out");
                    }
                }
                if (terminalResponse != null) {
                    onMessageReceived.messageReceived(TerminalUtilities.getString(terminalResponse).substring(2));
                    return terminalResponse;
                }
            }
        } catch (InterruptedException | IOException e) {
            triggerSendBlock(e,1);
            throw new ApiException("Error: " + e.getMessage());
        } catch (ApiException e) {
            triggerSendBlock(e,2);
            throw new ApiException("Error: " + e.getMessage());
        }
        return new byte[0];
    }

    @Override
    public void setMessageSentHandler(IMessageSentInterface messageInterface) {
        this.onMessageSent = messageInterface;
    }

    @Override
    public void setBroadcastMessageHandler(IBroadcastMessageInterface broadcastInterface) {
        this.onBroadcastMessage = broadcastInterface;
    }

    @Override
    public void setMessageReceivedHandler(IMessageReceivedInterface messageReceivedInterface) {
        this.onMessageReceived = messageReceivedInterface;
    }

    class ConnectTask implements Runnable {
        @Override
        public void run() {
            try {
                if (serverSocket != null) {
                    serverSocket.close();
                }
                serverSocket = new ServerSocket(port);
                try {
                    serverSocket.setSoTimeout(timeout);
                    socket = serverSocket.accept();

                    output = new DataOutputStream(
                            new BufferedOutputStream(socket.getOutputStream()));
                    input = new DataInputStream(
                            new BufferedInputStream(socket.getInputStream()));

                    new Thread(new AnalyzeReceivedDataTask()).start();
                } catch (Exception e) {
                    triggerSendBlock(e,3);
                }
            } catch (IOException e) {
                triggerSendBlock(e,4);
            }
        }
    }

    class AnalyzeReceivedDataTask implements Runnable {
        @Override
        public void run() {
            try {
                byte[] headerBuffer = new byte[2];
                while (serverSocket != null || !serverSocket.isClosed()) {

                    int readHeader = input.read(headerBuffer, 0, headerBuffer.length);

                    if (readHeader == -1) {
                        triggerSendBlock(new Exception("Error: Terminal disconnected"),7);
                        break;
                    }

                    int dataLength = TerminalUtilities.headerLength(headerBuffer);
                    byte[] tempBuffer = new byte[dataLength];

                    boolean incomplete = true;
                    int offset = 0;
                    int tempLength = dataLength;

                    do {
                        // Read data
                        int bytesReceived = input.read(tempBuffer, offset, tempLength);
                        if (bytesReceived != tempLength) {
                            offset += bytesReceived;
                            tempLength -= bytesReceived;
                        } else {
                            incomplete = false;
                        }
                    } while (incomplete);

                    byte[] dataBuffer = new byte[dataLength];
                    System.arraycopy(tempBuffer, 0, dataBuffer, 0, dataLength);

                    if (isBroadcast(dataBuffer)) {
                        broadcastMessage = new BroadcastMessage(dataBuffer);
                        onBroadcastMessage.broadcastReceived(broadcastMessage.getCode(),
                                broadcastMessage.getMessage());
                    } else if (isKeepAlive(dataBuffer) && new INGENICO_GLOBALS().KEEPALIVE) {
                        byte[] kResponse = keepAliveResponse(dataBuffer);
                        output.write(kResponse, 0, kResponse.length);
                        output.flush();
                    } else {
                        terminalResponse = dataBuffer;
                    }
                    headerBuffer = new byte[2];
                }
            } catch (InterruptedIOException e){
                triggerSendBlock(e,5);
            } catch (Exception e) {
                triggerSendBlock(e,6);
            }
        }
    }

    private boolean isBroadcast(byte[] buffer) {
        return TerminalUtilities.getString(buffer).contains(new INGENICO_GLOBALS().BROADCAST);
    }

    private boolean isKeepAlive(byte[] buffer) {
        return TerminalUtilities.getString(buffer).contains(new INGENICO_GLOBALS().TID_CODE);
    }

    private byte[] keepAliveResponse(byte[] buffer) {
        if (buffer.length > 0) {
            int tidIndex = TerminalUtilities.getString(buffer).indexOf(new INGENICO_GLOBALS().TID_CODE);
            String terminalId = TerminalUtilities.getString(buffer);
            String response = String.format(new INGENICO_GLOBALS().KEEP_ALIVE_RESPONSE, terminalId);
            response = TerminalUtilities.calculateHeader(response.getBytes(StandardCharsets.UTF_8)) + response;

            return response.getBytes(StandardCharsets.UTF_8);
        } else
            return null;
    }
}
