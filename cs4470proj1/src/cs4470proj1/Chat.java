package cs4470proj1;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;

public class Chat {

	/** Command tokens. */
	private enum Token {
		HELP		("help"),
		MYIP		("myip"),
		MYPORT		("myport"),
		CONNECT		("connect"),
		LIST		("list"),
		SEND		("send"),
		TERMINATE	("terminate"),
		EXIT		("exit");
		private String name;
		private Token(String name) { this.name = name; }
		public String getName() { return this.name; }
	}

	/** List of all connections, including closed connections. */
	private static ArrayList<Connection> connections = new ArrayList<>();

	/** The local port being used by the program */
	private static int localPort;

	// Main method.
	public static void main(String[] args) throws Exception {

		// Check if there is at least one argument.
		if (args.length < 1) {
			System.out.println("ERROR: Invalid launch parameters.");
			return;
		}

		// Check if the first argument (port number) is a non negative integer.
		if (!isNotNegativeInt(args[0])) {
			System.out.println("ERROR: " + args[0] + " is not a valid port.");
			return;
		}

		// Get local port number from the arguments.
		localPort = (Integer.parseInt(args[0]));

		// Create a new server thread and start it.
		Server server = new Server(localPort);
		server.start();

		// Create a new client thread and start it.
		Client client = new Client();
		client.start();

	}

	/** Handles incoming connections. */
	private static class Server extends Thread {

		/** The socket on the server that listens for incoming connections. */
		private ServerSocket listener;

		Server(int port) throws Exception {
			this.listener = new ServerSocket(port);
		}

		public void run() {
			try {
				System.out.println("PROGRAM RUNNING ON PORT " + this.listener.getLocalPort() + ".");

				// Keep listening for incoming connections.
				while (true) {

					// Accept incoming connection.
					Socket socket = listener.accept();
					System.out.println("Connection request from " + ipByteToString(socket.getInetAddress().getAddress()) + ":" + socket.getPort() + " was accepted.");
					synchronized (connections) {

						// If the connection does not exist yet (to the specific IP and port), then add the connection to the list.
						if (!connectionExists(socket.getInetAddress())) {
							Connection connection = new Connection(socket, connections.size());
							connections.add(connection);
							connection.start();
						}

						// If the connection already exist, then re
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
	private static class Client extends Thread {

		public void run() {
			BufferedReader userIn = new BufferedReader(new InputStreamReader(System.in));
			while (true) {
				try {
					
					String input = userIn.readLine(); // Get user input.
					synchronized (connections) {
						
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
						
						// list protocol
						else if (commandMatch(input, Token.LIST.getName())) {
							printList(); 
						}
						
						// connect protocol
						else if (commandMatch(input, Token.CONNECT.getName())) {
							try {
								connect(input, Token.CONNECT.getName());
							}
							catch (UnknownHostException e) {
								System.out.println("ERROR: Attempted to connected to unknown host.");
							}
							catch (ConnectException e) {
								System.out.println("ERROR: Could not connect.");
							}
							catch (SocketTimeoutException e) {
								System.out.println("ERROR: Could not connect.");
							}
						}
						
						// send protocol
						else if (commandMatch(input, Token.SEND.getName())) {
							try {
								send(input, Token.SEND.getName());
							}
							catch (Exception e) {
								
							}
						}
						
						// terminate protocol
						else if (commandMatch(input, Token.TERMINATE.getName())) {
							terminate(input, Token.TERMINATE.getName());
						}
						
						// exit protocol
						else if (commandMatch(input, Token.EXIT.getName())) {
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

	private static class Connection extends Thread {

		private Socket socket;
		private BufferedReader in;
		private PrintWriter out;
		private int index;

		Connection(Socket socket, int index) throws IOException {
			this.socket = socket;
			this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.out = new PrintWriter(socket.getOutputStream(), true);
            this.index = index;
		}
		
		public void run() {
			while (true) {
				try {
					String input = this.in.readLine();
					if (input == null) {
						this.socket.close(); // Receiving a null message means the other end of the socket was closed.
						System.out.println("Connection ID " + this.index + " was terminated.");
						break;
					}
					else if (commandMatch(input, Token.SEND.getName())) {
						String[] message = parseInput(input, Token.SEND.getName(), 1);
						if (message.length == 1) {
							System.out.println("Message received from " + ipByteToString(socket.getInetAddress().getAddress()));
							System.out.println("Sender's Port: " + this.socket.getPort());
							System.out.println("Message: \"" + message[0] + "\"");
						}
					}
				} catch (IOException e){

				}
			} 
		}

		public void send(String message) {
			out.println(Token.SEND.getName() + " " + message);
			System.out.println("Message sent to " + index + ".");
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
		
		// Check if there are active connections.
		boolean hasActiveConnections = false;
		if (connections.size() != 0) {
			for (Connection connection : connections) {
				if (!connection.socket.isClosed()) {
					hasActiveConnections = true;
					break;
				}
			}
		}
		
		// If there were no active connections, then print message and return.
		if (!hasActiveConnections) {
			System.out.println("No active connections.");
			return;
		}
		
		// If there were at least one active connection, then print the list of connections.
		System.out.println("id:\tIP Address  \tPort No.");
		for (Connection connection : connections) {
			Socket socket = connection.socket;
			if (socket.isClosed()) {
				continue;
			}
			System.out.println(connection.index + ":\t" + ipByteToString(socket.getInetAddress().getAddress()) + "\t" + socket.getPort());
		}
		
	}

	private static void connect(String input, String startsWith) throws Exception {
		String[] args = parseInput(input, startsWith, 2);
		if (args.length != 2) {
			System.out.println("Invalid syntax for connect <destination> <port no>.");
			return;
		}
		if (!isNotNegativeInt(args[1])) {
			System.out.println("Invalid Port");
			return;
		}
		String destIp = args[0];
		int destPort = Integer.valueOf(args[1]);
		if (!connectionExists(destIp)) {
			
			// Create a new socket, but start its connection yet.
			Socket socket = new Socket();
			
			// Bind the socket's local IP and port.
			socket.bind(new InetSocketAddress(InetAddress.getLocalHost(), localPort + connections.size() + 1));
			
			// Start the connection to the client with a timeout of 2 seconds.
			socket.connect(new InetSocketAddress(destIp, destPort), 2000);
			
			// Create new Connection and add it to the list.
			Connection connection = new Connection(socket, connections.size());
			connections.add(connection);
			
			// Start the connection.
			connection.start();
			
			// Print success message.
			System.out.println("Successfully connected to " + destIp + ":" + destPort + ".");
		}
		else {
			System.out.println("ERROR: Connection to " + destIp + " already exists.");
		}
	}
	
	private static void send(String input, String startsWith) throws Exception {
		String[] args = parseInput(input, startsWith, 2);
		if (args.length != 2) {
			System.out.println("Invalid syntax for send <connection id> <message>.");
			return;
		}
		if (!isNotNegativeInt(args[0])) {
			System.out.println("Invalid connection ID.");
			return;
		}
		Connection connection = connections.get(Integer.valueOf(args[0]));
		if (connection.socket.isClosed()) {
			System.out.println("ERROR: Connection ID " + args[0] + " is closed.");
			return;
		}
		connection.send(args[1]);
		
	}

	private static void terminate(String input, String startsWith) throws Exception {
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
		if (id < 0 || id >= connections.size()) {
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
		for (Connection connection : connections) {
			Socket socket = connection.socket;
			if (socket.isClosed()) {
				continue;
			}
			if (socket.getInetAddress().getHostAddress().equals(ip)) {
				return true;
			}
		}
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
		Connection connection = connections.get(id);
		if (!connection.socket.isClosed()) {
//			connection.out.println(Command.TERMINATE);
			connection.socket.close();
			return true;
		}
		if (printError) {
			System.out.println("ERROR: Connection ID " + id + " is already terminated.");
		}
		return false;
	}

	private static void dropAllConnections() throws Exception {
		for (int i = 0; i < connections.size(); i++) {
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

}
