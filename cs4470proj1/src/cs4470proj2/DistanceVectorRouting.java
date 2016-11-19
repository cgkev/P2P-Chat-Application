package cs4470proj2;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
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
		SEND		("send"),
		DISABLE	("terminate"),
		CRASH		("exit");
		private String name;
		private Token(String name) { this.name = name; }
		public String getName() { return this.name; }
	}

	private static ServerList serverList;

	/** The local port being used by the program */
	private static int localPort = 0;

	/** The id of this server */
	private static int serverId = 0;

	/** The routing update interval in miliseconds. */
	private static int routingUpdateInterval = -1;

	/** The countdown before the next routing update */
	private static long routingUpdateCountdown = -1;

	/** The countdown update interval in miliseconds. */
	private static int routingUpateCountdownInterval = 100;

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

		// Test byte conversion
		//		for (int i = 0; i < 10; i++) {
		//			short asdf = (short)(Math.random() * 30000);
		//			System.out.print("Original: " + asdf + ", ");
		//			byte[] b = shortToByte(asdf);
		//			System.out.print("Bytes: " + b[0] + " " + b[1] + ", ");
		//			short c = (short)byteToInt(b);
		//			System.out.println("Final: " + c + "\n");
		//		}

		Timer timer = new Timer();
		timer.scheduleAtFixedRate(new TimerTask() {

			@Override
			public void run() {
				routingUpdateCountdown -= routingUpateCountdownInterval;
				if (routingUpdateCountdown <= 0) {
					try {
						serverList.checkConnections();
						sendRoutingUpdate();
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		}, routingUpateCountdownInterval, routingUpateCountdownInterval);

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

						// myip protocol
						else if (commandMatch(input, Token.MYIP.getName())) {
							System.out.println(ipByteToString(InetAddress.getLocalHost().getAddress()));
						}

						// myport protocol
						else if (commandMatch(input, Token.MYPORT.getName())) {
							System.out.println(localPort);
						}

						// display protocol
						else if (commandMatch(input, Token.DISPLAY.getName())) {
							printList(); 
						}

						// update protocol
						else if (commandMatch(input, Token.UPDATE.getName())) {
							updateLinkCost(input.replace(Token.UPDATE.getName(), ""));
						}

						// disable protocol
						else if (commandMatch(input, Token.DISABLE.getName())) {
							disable(input, Token.DISABLE.getName());
						}

						// crash protocol
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
		//private boolean isNeighbor;
		private boolean disabled;
		private int linkCost;
		private int calculatedCost;
		private int nextHopId;
		private Connection connection;

		public Server(int id, String ipString, int port) {
			this.serverId = id;
			this.ipString = ipString;
			this.port = port;
			//this.isNeighbor = false;
			this.disabled = false;
			this.calculatedCost = Short.MAX_VALUE;
			this.linkCost = Short.MAX_VALUE;
			this.nextHopId = id;
		}

		// If the link cost is infinity, then the server is not a neighbor.
		public boolean isNeighbor() {
			return this.linkCost != Short.MAX_VALUE;
		}

		public boolean isConnected() throws IOException {
			if (this.connection == null) {
				return false;
			}
			if (this.connection.socket.isClosed()) {
				this.disconnect();
				return false;
			}
			return true;
		}

		public void connect() {
			if (!this.disabled && this.isNeighbor() && this.connection == null) {
				try {

					// Create a new socket, but do not start its connection yet.
					Socket socket = new Socket();

					// Start the connection to the client with a timeout of 2 seconds.
					socket.connect(new InetSocketAddress(this.ipString, this.port), 2000);

					this.connection = new Connection(socket);
					this.connection.start();

				}
				catch (UnknownHostException e) {
					System.out.println("ERROR: Attempted to connected to unknown host.");
				}
				catch (ConnectException e) {
					System.out.println("ERROR: Could not connect.");
				}
				catch (SocketTimeoutException e) {
					System.out.println("ERROR: Timed out while attempting to connect.");
				}
				catch (Exception e) {
					System.out.println("ERROR: Could not connect.");
				}
			}
		}

		public void connect(Socket socket) throws IOException {
			if (!this.disabled && this.connection != null && this.isNeighbor() && this.connection.socket.isClosed()) {
				this.disconnect();
				this.connection = new Connection(socket);
			}
		}

		public void disconnect() throws IOException {
			this.connection.socket.close();
			this.connection.stop = true;
			this.connection = null;
			this.linkCost = Short.MAX_VALUE; // The disconnected server will no longer be a neighbor.
		}

		public void send(byte[] message) throws IOException {
			try {
				this.connection.out.writeInt(message.length);
				this.connection.out.write(message);
			}
			catch (SocketException e) {
				this.disconnect();
			}
			catch (IOException e) {
				e.printStackTrace();
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

		Connection(Socket socket) throws IOException {
			this.socket = socket;
			this.in = new DataInputStream(socket.getInputStream());
			this.out = new DataOutputStream(socket.getOutputStream());
			this.stop = false;
		}

		public void run() {
			while (!stop) {
				try {
					String input = this.in.readLine();
					//					if (input == null) {
					//						this.socket.close(); // Receiving a null message means the other end of the socket was closed.
					//						System.out.println("Connection was terminated.");
					//						break;
					//					}
					//					else {
					int length = in.readInt();
					if(length > 0) {
						byte[] message = new byte[length];
						in.readFully(message, 0, message.length);
						for (byte data : message) {
							System.out.println(data);
						}
					}
					//					}
				} catch (IOException e){

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

		// Convert the fields in this Message into the byte representation of the message.
		public byte[] getByteMessage() {
			byte[] result = concatByteArrays(updateFieldsCount, serverPort);
			result = concatByteArrays(result, serverIp);
			for (byte[] server : servers) {
				result = concatByteArrays(result, server);
			}
			return result;
		}

		public int getUpdateFieldsCount() {
			return byteToInt(updateFieldsCount);
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
		System.out.println("\"myip\"");
		System.out.println("\"myport\"");
		System.out.println("\"connect <destination> <port no>\"");
		System.out.println("\"list\"");
		System.out.println("\"terminate <connection id.>\"");
		System.out.println("\"send <connection id.> <message>\"");
		System.out.println("\"exit\"");
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

	}

	private static void updateLinkCost(String input) throws Exception {
		String[] args = input.split(" ");
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
		System.out.println(newLinkCost);
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
		System.out.println("UPDATING");
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
		if (id < 0 || id >= serverList.servers.size()) {
			System.out.println("Connection ID " + id + " not found.");
			return;
		}
		if (dropConnection(id, true)) {
			System.out.println("Conneciton ID " + id + " was successfully terminated.");
		}
	}

	/** 
	 * Checks if a connection to an IP Address already exists.
	 * @param ip An InetAddress representation of the IP address.
	 * @return True, if a connection to the IP already exists. False otherwise.
	 */
	private static boolean connectionExists(InetAddress ip) {
		return connectionExists(ip.getHostAddress());
	}

	/** 
	 * Checks if a connection to an IP Address already exists.
	 * @param ip A String representation of the IP address.
	 * @return True, if a connection to the IP already exists. False otherwise.
	 */
	private static boolean connectionExists(String ip) {
		//		for (Server connection : serverList.servers) {
		//			Socket socket = connection.socket;
		//			if (socket.isClosed()) {
		//				continue;
		//			}
		//			if (socket.getInetAddress().getHostAddress().equals(ip)) {
		//				return true;
		//			}
		//		}
		return false;
	}

	/**
	 * Closes a connection if its ID exists and has not already been closed.
	 * @param id The ID of the connection.
	 * @param printError Whether to print an error message if the connection was already terminated.
	 * @return True if the connection was successfully close, false otherwise.
	 * @throws Exception
	 */
	private static boolean dropConnection(int id, boolean printError) throws Exception {
		//		Server connection = serverList.servers.get(id);
		//		if (!connection.socket.isClosed()) {
		//			//			connection.out.println(Command.TERMINATE);
		//			connection.socket.close();
		//			return true;
		//		}
		//		if (printError) {
		//			System.out.println("ERROR: Connection ID " + id + " is already terminated.");
		//		}
		return false;
	}

	private static void dropAllConnections() throws Exception {
		for (int i = 0; i < serverList.servers.size(); i++) {
			dropConnection(i, false);
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
				//				String[] neighborInfo = line.split(" ");
				//				Server server = serverList.findById(Integer.parseInt(neighborInfo[1]));
				//				if (server != null) {
				//					server.linkCost = Integer.parseInt(neighborInfo[2]);
				//					server.calculatedCost = server.linkCost;
				//				}
				updateLinkCost(line);
			}

		}
	}

}
