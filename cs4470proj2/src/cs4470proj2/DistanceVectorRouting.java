package cs4470proj2;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;

/**
 * CS 4470 Fall 2016 Project 2
 * @author Kevin Huynh
 * @author Alvin Quach
 * @author Robert Rosas
 * @author Mauricio Sandoval
 */
public class DistanceVectorRouting {

	/** Command tokens. */
	private enum Token {
		HELP		("help"),
		MYIP		("myip"),
		MYPORT		("myport"),
		CONNECT		("connect"),
		DISPLAY		("display"),
		UPDATE		("update"),
		STEP		("step"),
		DISABLE		("disable"),
		CRASH		("crash"),
		PACKETS		("packets"),
		EXIT		("exit"),
		COST		("cost");
		private String name;
		private Token(String name) { this.name = name; }
		public String getName() { return this.name; }
	}

	/** How many times the program should attempt to connect to a server before setting its link cost to infinity. */
	private static final int CONNECTION_ATTEMPTS = 4;

	private static final int COUNTDOWN_UPDATE_INTERVAL = 100;

	private static final boolean DEBUG = false;

	/** A container for the list of servers */
	private static ServerList serverList;

	/** The local port being used by the program */
	private static int localPort = 0;

	/** The id of this server */
	private static int serverId = 0;

	/** The routing update interval in miliseconds. */
	private static int routingUpdateInterval = -1;

	/** The countdown before the next routing update */
	private static long routingUpdateCountdown = -1;

	private static int messageCount;


	// Main method.
	public static void main(String[] args) throws Exception {

		// Check if there is at least four argument.
		// TODO Set default values that can be used if no parameters are specified.
		if (args.length < 4) {
			System.out.println("ERROR: Invalid launch parameters.");
			return;
		}

		// Parse launch parameters
		String topologyFileName = "";
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if ((i + 1) < args.length) {
				if (topologyFileName.isEmpty() && arg.equals("-t")) {
					topologyFileName = args[++i];
					continue;
				}
				if (routingUpdateInterval < 0 && arg.equals("-i")) {
					arg = args[++i];
					if (isNotNegativeInt(arg)) {
						routingUpdateInterval = Integer.parseInt(arg) * 1000;
						continue;
					}
					System.out.println("ERROR: " + arg + " is not a valid update interval.");
					return;
				}
			}
		}
		if (topologyFileName.isEmpty() || routingUpdateInterval < 0) {
			System.out.println("ERROR: Invalid launch parameters.");
			return;
		}

		File f = new File(topologyFileName);
		if (f.exists()) {
			parseTopologyFile(f);
			if (localPort == 0 || serverId == 0) {
				System.out.println("ERROR: Server info is undefined in topology file.");
				return;
			}
		}
		else {
			System.out.println("ERROR: Topology file not found.");
			return;
		}

		// Create a new server thread and start it.
		InboundConnectionHandler inboundConnectionHandler = new InboundConnectionHandler(localPort);
		inboundConnectionHandler.start();

		// Create a new client thread and start it.
		UserInputHandler userInputHandler = new UserInputHandler();
		userInputHandler.start();

		Timer timer = new Timer();
		timer.scheduleAtFixedRate(new TimerTask() {

			@Override
			public void run() {
				routingUpdateCountdown -= COUNTDOWN_UPDATE_INTERVAL;
				if (routingUpdateCountdown <= 0) {
					try {
						serverList.checkConnections();
						calculateRouting();
						sendRoutingUpdate();
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		}, COUNTDOWN_UPDATE_INTERVAL, COUNTDOWN_UPDATE_INTERVAL);

	}

	/** Handles incoming connections. */
	private static class InboundConnectionHandler extends Thread {

		/** The socket on the server that listens for incoming connections. */
		private ServerSocket listener;

		InboundConnectionHandler(int port) throws Exception {
			this.listener = new ServerSocket(port);
		}

		public void run() {
			try {
				System.out.println("PROGRAM RUNNING ON PORT " + this.listener.getLocalPort() + ".");

				// Keep listening for incoming connections.
				while (true) {

					// Accept incoming connection.
					Socket socket = listener.accept();
					synchronized (serverList) {
						Server server = serverList.findByIp(socket.getInetAddress());
						if (server != null && !server.isConnected()) {
							server.connect(socket);
						}
						else {
							System.out.println("CONNECTION REFUSED BECAUSE IT ALREADY EXISTS");
							socket.close();
						}
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/**  Handles user input. */
	private static class UserInputHandler extends Thread {

		public void run() {
			BufferedReader userIn = new BufferedReader(new InputStreamReader(System.in));
			while (true) {
				try {

					String input = userIn.readLine(); // Get user input.
					synchronized (serverList) {

						// help protocol
						if (commandMatch(input, Token.HELP.getName())) {
							printHelp();
						}

						// packets protocol
						if (commandMatch(input, Token.PACKETS.getName())) {
							System.out.println(messageCount + "packets received since last check.");
							messageCount = 0;
						}

						// myip protocol
						else if (commandMatch(input, Token.MYIP.getName())) {
							System.out.println(ipByteToString(InetAddress.getLocalHost().getAddress()));
						}

						// myport protocol
						else if (commandMatch(input, Token.MYPORT.getName())) {
							System.out.println(localPort);
						}

						// cost protocol (for debug)
						else if (commandMatch(input, Token.COST.getName())) {
							for (Server server : serverList.servers) {
								System.out.println(server.serverId + " : " + server.linkCost);
							}
						}

						// display protocol
						else if (commandMatch(input, Token.DISPLAY.getName())) {
							printList(); 
						}

						// update protocol
						else if (commandMatch(input, Token.UPDATE.getName())) {
							updateLinkCost(input.replace(Token.UPDATE.getName(), ""));
						}

						// step protocol
						else if (commandMatch(input, Token.STEP.getName())) {
							sendRoutingUpdate();
						}

						// disable protocol
						else if (commandMatch(input, Token.DISABLE.getName())) {
							disable(input, Token.DISABLE.getName());
						}

						// crash protocol
						else if (commandMatch(input, Token.CRASH.getName())) {
							dropAllConnections();
							System.out.println("Closed all connections. There are no more neighbors. You can reconnect to servers by updating the cost to a server.");
						}

						// exit protocol
						else if (commandMatch(input, Token.CRASH.getName())) {
							dropAllConnections();
							System.out.println("Exiting...");
							System.exit(0);
						}

						// unknown protocol
						else {
							int end = input.indexOf(' ');
							System.out.println("Unknown command: " + (end < 0 ? input : input.substring(0, end)));
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}

		}
	}

	/**
	 * 
	 * @author Alvin
	 *
	 */
	private static class Server implements Comparable<Server> {

		private int serverId;
		private String ipString;
		private int port;
		private boolean disabled;
		private int linkCost;
		private int calculatedCost;
		private int nextHopId;
		private Connection connection;
		private int connectionAttempts;
		private Message lastMessage;
		private boolean connectionInitialized; // Whether there had been a connection to this server, regardless of the current status.

		public Server(int id, String ipString, int port) {
			this.serverId = id;
			this.ipString = ipString;
			this.port = port;
			this.disabled = false;
			this.calculatedCost = Short.MAX_VALUE;
			this.linkCost = Short.MAX_VALUE;
			this.nextHopId = id;
			this.connectionAttempts = 0;
			this.connectionInitialized = false;
		}

		// If the link cost is infinity, then the server is not a neighbor.
		public boolean isNeighbor() {
			return this.linkCost != Short.MAX_VALUE;
		}

		public boolean isConnected() throws IOException {
			if (this.connection == null) {
				//				System.out.println("CONNECTION IS NULL");
				return false;
			}
			if (this.connection.stop) {
				//				System.out.println("connection has been stopped");
				this.resetConnection();
				return false;
			}
			if (this.connection.socket.isClosed()) {
				//				System.out.println("connection is closed");
				this.resetConnection();
				return false;
			}
			return true;
		}

		public void connect() {
			if (!this.disabled && this.isNeighbor() && this.connection == null) {
				try {

					if (DEBUG) System.out.println("DEBUG: Attempting to connect to " + this.ipString + ":" + this.port +", ID " + this.serverId + ".");

					// Create a new socket, but do not start its connection yet.
					Socket socket = new Socket();

					// Start the connection to the client with a timeout of 2 seconds.
					socket.connect(new InetSocketAddress(this.ipString, this.port), 2000);

					this.connection = new Connection(socket, this);
					this.connection.start();

				}
				catch (UnknownHostException e) {
					this.connectionAttempts++;
					System.out.println("ERROR: Attempted to connected to unknown host.");
					checkConnectionAttempts();
				}
				catch (ConnectException e) {
					this.connectionAttempts++;
					System.out.println("ERROR: Could not connect to ID " + this.serverId + ". Attempted " + this.connectionAttempts + " times.");
					checkConnectionAttempts();
				}
				catch (SocketTimeoutException e) {
					this.connectionAttempts++;
					System.out.println("ERROR: Timed out while attempting to connect to ID " + this.serverId + ". Attempted " + this.connectionAttempts + " times.");
					checkConnectionAttempts();
				}
				catch (Exception e) {
					this.connectionAttempts++;
					System.out.println("ERROR: Could not connect to ID " + this.serverId + ". Attempted " + this.connectionAttempts + " times.");
					checkConnectionAttempts();
				}
			}
		}

		public void connect(Socket socket) throws IOException {
			if (!this.disabled && this.isNeighbor() && (this.connection == null || this.connection.socket.isClosed() || !this.connection.socket.isConnected())) {
				this.resetConnection();
				this.connection = new Connection(socket, this);
				this.connection.start();
			}
			else {
				socket.close();
			}
		}

		public void resetConnection() {
			try {
				if (this.connection != null) {
					this.connection.socket.close();
					this.connection.stop = true;
					this.connection = null;
				}
				this.connectionAttempts++;
				checkConnectionAttempts();
			}
			catch (SocketException e) {
				System.out.println("cant disconnect?");
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		}

		public void send(byte[] message) throws IOException {
			try {
				this.connection.out.writeInt(message.length);
				this.connection.out.write(message);
				if (DEBUG) System.out.println("DEBUG: Sent " + message.length + " bytes to " + this.ipString + ":" + this.port + ", ID " + this.serverId + ".");
			}
			catch (SocketException e) {
				System.out.println("ERROR: Connection to server " + this.serverId + " was lost. Attempted to reconnect " + (this.connectionAttempts + 1) + " times.");
				this.resetConnection();
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		}

		// If the server had been previously connected, but now can't connect after a certain amount of attempts, then it is considered to be disconnected.
		private void checkConnectionAttempts() {
			if (this.connectionAttempts >= CONNECTION_ATTEMPTS) {
				if (this.connectionInitialized) {
					System.out.println("Server " + this.serverId + " could not be reconnected to and is no longer a neighbor.");
				}
				else {
					System.out.println("Server " + this.serverId + " could not be connected to and is no longer a neighbor.");
				}
				this.linkCost = Short.MAX_VALUE; // The disconnected server will no longer be a neighbor.
				//				this.calculatedCost = Short.MAX_VALUE;
				//				this.nextHopId = this.serverId;
				resetCalculatedCosts();
			}
		}

		@Override
		public int compareTo(Server o) {
			return this.serverId - o.serverId;
		}
	}

	private static class Connection extends Thread {

		private Socket socket;
		private DataInputStream in;
		private DataOutputStream out;
		private boolean stop;
		private Server server; // Reference to the parent server.

		Connection(Socket socket, Server server) throws IOException {
			this.socket = socket;
			this.in = new DataInputStream(socket.getInputStream());
			this.out = new DataOutputStream(socket.getOutputStream());
			this.stop = false;
			this.server = server;
		}

		public void run() {
			while (!stop) {
				try {
					if (in.available() > 0) {
						this.server.connectionInitialized = true;
						this.server.connectionAttempts = 0;
						int length = in.readInt();
						if(length > 0) {
							byte[] byteMessage = new byte[length];
							in.readFully(byteMessage, 0, byteMessage.length);
							Message message = Message.getMessageFromBytes(byteMessage);
							if (DEBUG) {
								System.out.print("DEBUG: Received message containing " + length + " bytes from " + InetAddress.getByAddress(message.serverIp).getHostAddress() + ":" + byteToInt(message.serverPort) + ", ID " + this.server.serverId + ":\n\t");
								for (byte data : byteMessage) {
									System.out.print(data + " ");
								}
								System.out.println();
								for (int i = 0; i < message.servers.length; i++) {
									System.out.println("\tID: " + message.getServerIdByIndex(i) + ", IP: " + InetAddress.getByAddress(message.getServerIpByIndex(i)).getHostAddress() + ", Port: " + message.getServerPortByIndex(i) + ", Cost: " + message.getServerCostByIndex(i));
								}
							}
							this.server.lastMessage = message;
							messageCount++;
						}
					}
				} catch (IOException e){
					if (DEBUG) System.out.println("DEBUG: Socket connection to server " + this.server.serverId + " was lost.");
					this.stop = true;
				}
			} 
		}
	}

	private static class ServerList {

		private TreeSet<Server> servers;

		public ServerList(TreeSet<Server> servers) {
			this.servers = servers;
		}

		public void checkConnections() throws IOException {
			for (Server server : this.servers) {
				if (!server.isConnected()) {
					server.connect();
				}
			}
		}

		public Server findById(int serverId) {
			for (Server server : servers) {
				if (server.serverId == serverId) {
					return server;
				}
			}
			return null;
		}

		public Server findByIp(InetAddress address) {
			String ip = address.getHostAddress();
			for (Server server : servers) {
				if (server.ipString.equals(ip)) {
					return server;
				}
			}
			return null;
		}
	}

	/** Data structure for sending routing updates */
	private static class Message {

		private byte[] updateFieldsCount;
		private byte[] serverPort;
		private byte[] serverIp;
		private byte[][] servers;

		private Message() {

		}

		public Message(int updateFieldsCount, int serverPort, byte[] serverIp, Server[] servers) throws UnknownHostException {
			this.updateFieldsCount = shortToByte((short)updateFieldsCount);
			this.serverPort = shortToByte((short)localPort);
			this.serverIp = InetAddress.getLocalHost().getAddress();
			this.servers = new byte[serverList.servers.size() + 1][];
			this.servers[0] = generateServerByteInfo(InetAddress.getLocalHost(), localPort, serverId, 0);
			int i = 1;
			for (Server server : serverList.servers) {
				this.servers[i++] = generateServerByteInfo(InetAddress.getByName(server.ipString), server.port, server.serverId, server.calculatedCost);
			}
		}

		/** Converts the fields in this Message into the byte representation of the message. */
		public byte[] getByteMessage() {
			byte[] result = concatByteArrays(updateFieldsCount, serverPort);
			result = concatByteArrays(result, serverIp);
			for (byte[] server : servers) {
				result = concatByteArrays(result, server);
			}
			return result;
		}

		/** Converts a byte array back into a Message */
		public static Message getMessageFromBytes(byte[] byteMessage) {
			if (byteMessage.length < 20) {
				if (DEBUG) System.out.println("DEBUG: Message length of " + byteMessage.length + " is too short.");
				return null;
			}
			if (byteMessage.length % 4 != 0) {
				if (DEBUG) System.out.println("DEBUG: Message length of " + byteMessage.length + " is invalid.");
				return null;
			}
			Message message = new Message();
			message.updateFieldsCount = Arrays.copyOfRange(byteMessage, 0, 2);
			message.serverPort = Arrays.copyOfRange(byteMessage, 2, 4);
			message.serverIp = Arrays.copyOfRange(byteMessage, 4, 8);
			message.servers = new byte[byteToInt(message.updateFieldsCount)][];
			for (int i = 0; i < message.servers.length; i++) {
				message.servers[i] = Arrays.copyOfRange(byteMessage, 8 + i * 12, 20 + i * 12);
			}
			return message;
		}

		public byte[] getServerIpByIndex(int index) {
			if (index < -1 || index > servers.length - 1) {
				return new byte[4];
			}
			else {
				return Arrays.copyOfRange(this.servers[index], 0, 4);
			}
		}

		public int getServerPortByIndex(int index) {
			if (index < -1 || index > servers.length - 1) {
				return -1;
			}
			else {
				return byteToInt(Arrays.copyOfRange(this.servers[index], 4, 6));
			}
		}

		public int getServerIdByIndex(int index) {
			if (index < -1 || index > servers.length - 1) {
				return -1;
			}
			else {
				return byteToInt(Arrays.copyOfRange(this.servers[index], 8, 10));
			}
		}

		public int getServerCostByIndex(int index) {
			if (index < -1 || index > servers.length - 1) {
				return -1;
			}
			else {
				return byteToInt(Arrays.copyOfRange(this.servers[index], 10, 12));
			}
		}

		public int getServerCostById(int id) {
			for (int i = 0; i < this.servers.length; i++) {
				if (byteToInt(Arrays.copyOfRange(this.servers[i], 8, 10)) == id) {
					return byteToInt(Arrays.copyOfRange(this.servers[i], 10, 12));
				}
			}
			return Short.MAX_VALUE;
		}

		private byte[] generateServerByteInfo(InetAddress ip, int port, int id, int cost) {
			byte[] ipByte = ip.getAddress();
			byte[] portByte = shortToByte((short)port);
			byte[] idByte = shortToByte((short)id);
			byte[] costByte = shortToByte((short)cost);

			byte[] result = concatByteArrays(ipByte, portByte);
			result = concatByteArrays(result, new byte[] {0, 0});
			result = concatByteArrays(result, idByte);
			result = concatByteArrays(result, costByte);
			return result;
		}

		// Borrowed from http://stackoverflow.com/questions/5368704/appending-a-byte-to-the-end-of-another-byte
		private byte[] concatByteArrays(byte[] a, byte[] b) {
			byte[] result = new byte[a.length + b.length]; 
			System.arraycopy(a, 0, result, 0, a.length); 
			System.arraycopy(b, 0, result, a.length, b.length); 
			return result;
		}

	}

	private static void printHelp() {
		System.out.println("Avaiable commands are:");
		System.out.println("\"step\"");
		System.out.println("\"packets\"");
		System.out.println("\"display\"");
		System.out.println("\"disable <server-ID>\"");
		System.out.println("\"crash\"");
		System.out.println();
	}

	private static void printList() {

		if (serverList.servers.size() == 0) {
			System.out.println("No servers.");
			return;
		}

		// Print header
		System.out.println("\nDest ID\t\tNext Hop ID\tCost of Path");

		// Print list of servers.
		boolean printedThisServer = false;
		for (Server server : serverList.servers) {
			if (!printedThisServer && server.serverId > serverId) {
				System.out.println(" " + serverId + "\t\t " + serverId + "\t\t 0");
				printedThisServer = true;
			}
			String cost = (server.calculatedCost == Short.MAX_VALUE ? "infinity" : String.valueOf(server.calculatedCost));
			System.out.println(" " + server.serverId + "\t\t " + server.nextHopId + "\t\t " + cost);
		}
		if (!printedThisServer) {
			System.out.println(" " + serverId + "\t\t " + serverId + "\t\t 0");
			printedThisServer = true;
		}

	}

	private static void updateLinkCost(String input) throws Exception {
		String[] args = input.trim().split(" ");
		if (args.length != 3) {
			System.out.println("Invalid syntax for: update <server-ID1> <server-ID2> <Link Cost>.");
			return;
		}
		if (!isNotNegativeInt(args[0]) || !isNotNegativeInt(args[1])) {
			System.out.println("Invalid server ID number(s).");
			return;
		}
		int linkedServerId = -1;
		if (Integer.parseInt(args[0]) == serverId) {
			linkedServerId = Integer.parseInt(args[1]);
		}
		else if (Integer.parseInt(args[1]) == serverId) {
			linkedServerId = Integer.parseInt(args[0]);
		}
		else {
			System.out.println("The requested update does not involve this server (ID " + serverId + ").");
			return;
		}
		Server server = serverList.findById(linkedServerId);
		if (server == null) {
			System.out.println("Server ID " + linkedServerId + " does not exist.");
			return;
		}
		int newLinkCost = server.linkCost;
		if (args[2].equals("inf")) {
			newLinkCost = Short.MAX_VALUE;
		}
		else if (isNotNegativeInt(args[2])) {
			newLinkCost = Integer.parseInt(args[2]);
		}
		else {
			System.out.println("Invalid link cost.");
			return;
		}
		server.linkCost = newLinkCost;

		// Also reset the calculate cost and next-hop ID.
		resetCalculatedCosts();

		System.out.println("Updated server ID " + server.serverId + " link cost to " + newLinkCost);
	}

	private static void resetCalculatedCosts() {
		for (Server server : serverList.servers) {
			server.calculatedCost = Short.MAX_VALUE;
			server.nextHopId = server.serverId;
		}
	}

	/** Implementation of Bellman-Ford algorithm */
	private static void calculateRouting() {
		synchronized (serverList) {

			// Calculate the cost for each of the servers.
			for (Server server : serverList.servers) {

				// Set the initial minimum cost amount and next-hop to the previously calculated values.
				int minCost = server.calculatedCost;
				int minCostId = server.serverId;

				// Go through the list of neighboring servers to check their cost to the destination.
				for (Server neighbor : serverList.servers) {

					// If the server is not a neighbor, then continue to the next server.
					if (!neighbor.isNeighbor()) {
						continue;
					}

					Message message = neighbor.lastMessage;
					if (message != null) {

						// The cost of this route is the sum of the link cost to the neighbor and the cost from the neighbor to the destination.
						int cost = neighbor.linkCost + message.getServerCostById(server.serverId);

						if (neighbor.serverId == server.nextHopId) {
							if (cost > server.calculatedCost) {
								resetCalculatedCosts();
								return;
							}
						}

						if (cost < minCost) {
							minCost = cost;
							minCostId = neighbor.serverId;
						}
					}
				}
				server.calculatedCost = minCost;
				server.nextHopId = minCostId;
			}

		}
	}

	private static void sendRoutingUpdate() throws Exception {
		Message message = new Message(serverList.servers.size() + 1, localPort, InetAddress.getLocalHost().getAddress(), serverList.servers.toArray(new Server[serverList.servers.size()]));
		byte[] byteMessage = message.getByteMessage();
		for (Server server : serverList.servers) {
			if (server.isNeighbor() && server.isConnected()) {
				server.send(byteMessage);
			}
		}
		routingUpdateCountdown = routingUpdateInterval;
	}

	private static void disable(String input, String startsWith) throws Exception {
		String[] args = parseInput(input, startsWith, 1);
		if (args.length != 1) {
			System.out.println("Invalid syntax for terminate <connection id>.");
			return;
		}
		if (!isNotNegativeInt(args[0])) {
			System.out.println("Invalid connection ID.");
			return;
		}
		int id = Integer.valueOf(args[0]);
		if (id < 0 || id > serverList.servers.size() + 1) {
			System.out.println("Connection ID " + id + " not found.");
			return;
		}
		if (dropConnection(id, true)) {
			System.out.println("Conneciton ID " + id + " was successfully disabled.");
		}
	}

	/**
	 * Closes a connection if its ID exists and has not already been closed.
	 * @param id The ID of the connection.
	 * @param printError Whether to print an error message if the connection was already terminated.
	 * @return True if the connection was successfully close, false otherwise.
	 * @throws Exception
	 */
	private static boolean dropConnection(int id, boolean printError) throws Exception {
		Server server = serverList.findById(id);
		if (server != null) { 
			if (server.isConnected()) {
				server.connection.socket.close();
				server.connection.stop = true;
				server.connection = null;
				server.linkCost = Short.MAX_VALUE;
				server.calculatedCost = Short.MAX_VALUE;
				server.disabled = true;
				return true;
			}
			else if (printError) {
				System.out.println("ERROR: Connection ID " + id + " is not currently connected.");
			}
		}
		else if (printError) {
			System.out.println("ERROR: Connection ID " + id + " does not exist.");
		}
		return false;
	}

	private static void dropAllConnections() throws Exception {
		for (Server server : serverList.servers) {
			dropConnection(server.serverId, false);
		}
	}

	private static String ipByteToString(byte[] ipByte) {
		if (ipByte.length == 4) {
			String ip = "";
			for (int i = 0; i < 4; i++) {
				ip += (ipByte[i] & 0xFF) + (i <= 2 ? "." : "");
			}
			return ip;
		}
		return "INVALID IP BYTE LENGTH";
	}

	// Converts a short to an array of 2 bytes.
	private static byte[] shortToByte(short value) {
		return new byte[] {
				(byte)((value >> 8) & 0xFF),
				(byte)((value) & 0xFF)
		};
	}

	// Converts an array to 2 bytes to a int.
	private static int byteToInt(byte[] value) {
		if (value.length == 2) {
			return (((value[0] & 0xFF) << 8) | (value[1] & 0xFF));
		}
		return -1;
	}

	/**
	 * Parses a String for command arguments.
	 * @param input The input String.
	 * @param startsWith The expected starting substring of the input String.
	 * @param argCount The expected argument count of the string.
	 * @return An array of String containing the parsed arguments, or an empty array if the input String
	 * does not match the expected starting substring or the expected argument count.
	 */
	private static String[] parseInput(String input, String startsWith, int argCount) {
		String[] args = new String[argCount];
		String temp = input.replace(startsWith, "");
		if (temp.length() == 0 || temp.charAt(0) != ' ') {
			return new String[0];
		}
		temp = temp.substring(1);
		for (int i = 0; i < argCount; i++) {
			if (i < argCount - 1) {
				int delimIndex = temp.indexOf(" ");
				if (delimIndex < 0) {
					return new String[0];
				}
				String arg = temp.substring(0, delimIndex);
				if (arg.isEmpty()) {
					return new String[0];
				}
				args[i] = arg;
				temp = temp.substring(delimIndex + 1);
			}
			else {
				args[i] = temp;
			}
		}
		return args;
	}

	/**
	 * Checks if a String matches the pattern required for a specified.
	 * @param input The input string.
	 * @param command The command to compare the input string with.
	 * @return
	 */
	private static boolean commandMatch(String input, String command) {
		if (input.trim().equals(command)) {
			return true;
		}
		if (input.startsWith(command + " ")) {
			return true;
		}
		return false;
	}

	/**
	 * Checks if an input string is a non-negative integer.
	 * @param string the input string
	 * @return <code>true</code> if the string is a non-negative integer, <code>false</code> otherwise
	 */
	private static boolean isNotNegativeInt(String string) {
		if ((string.isEmpty()) || (string == null)) {
			return false;
		}
		for(int i=0; i<string.length(); i++) {
			if(!Character.isDigit(string.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Parses the topology file and updates the list of servers.
	 * Currently, error checking is NOT implemented.
	 * @param file The topology file.
	 * @throws Exception 
	 */
	private static void parseTopologyFile(File file) throws Exception {
		BufferedReader br = new BufferedReader(new FileReader(file.getAbsolutePath()));
		int expectedServerCount = 0;
		int lineNumber = 0;
		while (true) {
			String line = br.readLine();
			if (line == null) {
				break;
			}
			if (line.startsWith("//")) {
				continue;
			}
			lineNumber++;
			if (lineNumber == 1) {
				// Create new ArrayList with capacity equal to the number of expected servers.
				expectedServerCount = Integer.parseInt(line);
				serverList = new ServerList(new TreeSet<>());
			}
			else if (lineNumber == 2) {
				//				expectedNeighbors = Integer.parseInt(line);
			}
			else if (lineNumber > 2 && lineNumber <= 2 + expectedServerCount) {
				String[] serverInfo = line.split(" ");

				// If the server info is describing this server...
				String myip = ipByteToString(InetAddress.getLocalHost().getAddress());
				if (serverInfo[1].equals(myip)) {
					serverId = Integer.parseInt(serverInfo[0]);
					localPort = Integer.parseInt(serverInfo[2]);
				}

				// If the server info is describing another server...
				else {
					Server server = new Server(Integer.parseInt(serverInfo[0]), serverInfo[1], Integer.parseInt(serverInfo[2]));
					serverList.servers.add(server);
				}

			}
			else if (lineNumber > 2 + expectedServerCount) {
				updateLinkCost(line);
			}
		}
		br.close();
	}

}
