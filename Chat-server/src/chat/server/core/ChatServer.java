package chat.server.core;

import chat.common.Library;
import chat.network.ServerSocketThread;
import chat.network.ServerSocketThreadListener;
import chat.network.SocketThread;
import chat.network.SocketThreadListener;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Vector;

public class ChatServer implements ServerSocketThreadListener, SocketThreadListener {

    private final ChatServerListener listener;
    private ServerSocketThread server;
    private final DateFormat DATE_FORMAT = new SimpleDateFormat("HH:mm:ss: ");
    private Vector <SocketThread> clients = new Vector<>();

    public ChatServer(ChatServerListener listener) {
        this.listener = listener;
    }

    public void start(int port) {
        if (server != null && server.isAlive())
            putLog("Server already started");
        else
            server = new ServerSocketThread(this,"Server", port, 2000);
    }

    public void stop() {
        if (server == null || !server.isAlive()) {
            putLog("Server is not running");
        } else {
            server.interrupt();
        }
    }

    private void putLog (String msg) {
        msg = DATE_FORMAT.format(System.currentTimeMillis()) +
                Thread.currentThread().getName() + ":" + msg;
        listener.onChatServerMessage(msg);
    }

    /**
     * Server methods
     *
     * */

    @Override
    public void onServerStart(ServerSocketThread thread) {
        putLog("Server thread started");
        SqlClient.connect();

    }

    @Override
    public void onServerStopped(ServerSocketThread thread) {
        putLog("Server thread stopped");
        SqlClient.disconnect();
        for (int i = 0; i < clients.size(); i++) {
            clients.get(i).close();
        }
    }

    @Override
    public void onServerSockedCreated(ServerSocketThread thread, ServerSocket server) {
        putLog("Server socked created");
    }

    @Override
    public void onServerTimeout(ServerSocketThread thread, ServerSocket server) {
        //putLog("Server timeout");
    }

    @Override
    public void onSocketAccepted(ServerSocketThread thread, ServerSocket server, Socket socket) {
        putLog("Client connected");
        String name = "SocketThread " + socket.getInetAddress() +
                ":" + socket.getPort();
        new ClientThread( this, name, socket);
    }

    @Override
    public void onServerException(ServerSocketThread thread, Throwable exception) {
        exception.printStackTrace();
    }

    /**
     * Socket methods
     *
     * */

    @Override
    public synchronized void onSocketThreadStart(SocketThread thread, Socket socket) {
        putLog("Socket created");
    }

    @Override
    public synchronized void onSocketThreadStop(SocketThread thread) {
        ClientThread client = (ClientThread) thread;
        clients.remove(thread);
        if (client.isAuthorized() && !client.isReconnecting()) {
            sendToAuthClients(Library.getTypeBroadcast("Server",
                    client.getNickname() + " disconnected"));
        }
        sendToAuthClients(Library.getUserList(getUsers()));
    }

    @Override
    public synchronized void onSocketThreadReady(SocketThread thread, Socket socket) {
        clients.add(thread);
    }

    @Override
    public synchronized void onReceiveString(SocketThread thread, Socket socket, String msg) {
        ClientThread client = (ClientThread) thread;
        if (client.isAuthorized()) {
            handleAuthMessage(client, msg);
        } else
            handleNonAuthMessage(client, msg);
    }

    private void handleNonAuthMessage (ClientThread client, String msg) {
        String arr[] = msg.split(Library.DELIMITER);
        if (arr.length != 3 || !arr[0].equals(Library.AUTH_REQUEST)) {
            client.msgFormatError(msg);
            return;
        }
        String login = arr[1];
        String password = arr[2];
        String nickname = SqlClient.getNickname(login,password);
        if(nickname == null) {
            putLog("Invalid login attempt " + login);
            client.authFail();
            return;
        } else {
            ClientThread oldClient = findClientByNickname(nickname);
            client.authAccept(nickname);
            if(oldClient == null) {
                sendToAuthClients(Library.getTypeBroadcast("Server", nickname));
            } else {
                oldClient.reconnect();
                clients.remove(oldClient);
            }
        }
        client.authAccept(nickname);
        sendToAuthClients(Library.getUserList(getUsers()));
        sendToAuthClients(Library.getTypeBroadcast("Server", nickname + " connected"));

    }

    private void handleAuthMessage(ClientThread client, String msg) {
        String[] arr = msg.split(Library.DELIMITER);
        String msgType = arr[0];
        switch (msgType) {
            case Library.TYPE_BCAST_CLIENT:
                sendToAuthClients(Library.getTypeBroadcast(
                        client.getNickname(), arr[1]));
                break;
            default:
                client.sendMessage(Library.getMsgFormatError(msg));
        }
    }

    private void sendToAuthClients(String msg) {
        for (int i = 0; i < clients.size(); i++) {
            ClientThread client = (ClientThread) clients.get(i);
            if(!client.isAuthorized()) continue;
            client.sendMessage(msg);
        }
    }

    @Override
    public synchronized void onSocketThreadException(SocketThread thread, Exception exception) {
        exception.printStackTrace();
    }

    private String getUsers() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clients.size(); i++) {
            ClientThread client = (ClientThread) clients.get(i);
            if (!client.isAuthorized()) continue;
            sb.append(client.getNickname()).append(Library.DELIMITER);
        }
        return sb.toString();
    }

    private synchronized ClientThread findClientByNickname(String nickname) {
        for (int i = 0; i < clients.size(); i++) {
            ClientThread client = (ClientThread) clients.get(i);
            if (!client.isAuthorized()) continue;
            if (client.getNickname().equals(nickname))
                return client;
        }
        return null;
    }
}
