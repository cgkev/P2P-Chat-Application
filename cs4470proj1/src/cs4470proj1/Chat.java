package cs4470proj1;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;

import javax.sql.ConnectionEvent;

public class Chat {

	// List of commands.
	private enum Command {
		HELP		("help"),
		MYIP		("myip"),
		MYPORT		("myport"),
		CONNECT		("connect"),
		LIST		("list"),
		SEND		("send"),
		TERMINATE	("terminate"),
		EXIT		("exit");
		private String name;
		private Command(String name) { this.name = name; }
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
			System.out.println("INSERT ERROR MESSAGE HERE");
			return;
		}

		// Check if the first argument (port number) is a non negative integer.
		if (!isNotNegativeInt(args[0])) {
			System.out.println("INSERT ERROR MESSAGE HERE");
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

	/**
	 * Handles incoming connections.
	 */
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
					System.out.println("Incoming connection requested from " + ipByteToString(socket.getInetAddress().getAddress()) + ":" + socket.getPort());
					synchronized (connections) {

						// If the connection does not exist yet (to the specific IP and port), then add the connection to the list.
						if (!connectionExists(socket.getInetAddress(), socket.getPort())) {
							Connection connection = new Connection(socket);
							connections.add(connection);
							connection.start();
						}

						// If the connection already exist, then re
						else {
							socket.close();
							System.out.println("INSERT ERROR MESSAGE HERE");
						}
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Handles user input.
	 */
	private static class Client extends Thread {

		public void run() {
			BufferedReader userIn = new BufferedReader(new InputStreamReader(System.in));
			while (true) {
				try {
					String input = userIn.readLine().trim();
					synchronized (connections) {
						if (input.equals(Command.HELP.getName())) {
							printHelp();
						}
						else if (input.equals(Command.MYIP.getName())) {
							System.out.println(ipByteToString(InetAddress.getLocalHost().getAddress()));
						}
						else if (input.equals(Command.MYPORT.getName())) {
							System.out.println(localPort);
						}
						else if (input.equals(Command.LIST.getName())) {
							printList(); 
						}
						else if (input.startsWith(Command.CONNECT.getName())) {
							try {
								connect(input, Command.CONNECT.getName());
							}
							catch (UnknownHostException e) {
								System.out.println("ERROR: Attempted to connected to unknown host.");
							}
							catch (ConnectException e) {
								System.out.println("ERROR: Could not connect.");
							}
						}
						else if (input.startsWith(Command.SEND.getName())) {
							try {
								send(input, Command.SEND.getName());
							}
							catch (Exception e) {
								
							}
						}
						else if (input.startsWith(Command.TERMINATE.getName())) {
							terminate(input, Command.TERMINATE.getName());
						}
						else if (input.startsWith(Command.EXIT.getName())) {
							dropAllConnections();
							System.exit(0);
						}
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

		Connection(Socket socket) throws IOException {
			this.socket = socket;
			this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.out = new PrintWriter(socket.getOutputStream(), true);
		}
		
		public void run() {
				while (true) {
					try {
					String input = this.in.readLine();
					if (input.startsWith(Command.SEND.getName())) {
						String[] message = parseInput(input, Command.SEND.getName(), 1);
						if (message.length == 1) {
							System.out.println(message);
						}
					}
				} catch (IOException e){
					
				}
			} 
		}
	}

	private static void printHelp() {
		System.out.println("INSERT HELP MESSAGE HERE");
	}

	private static void printList() {
		if (connections.size() == 0) {
			System.out.println("No active connections.");
			return;
		}
		// TODO Fix formatting
		int id = 0; // Should ID start at 1?
		System.out.println("id: IP Address\t\t\t\tPort No.");
		for (Connection connection : connections) {
			Socket socket = connection.socket;
			// TODO: Properly implement timeout to check if connection is broken.
			if (socket.isClosed()) {
				continue;
			}
			System.out.println(id + ": " + ipByteToString(socket.getInetAddress().getAddress()) + "\t" + socket.getPort());
			id++;
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
		if (!connectionExists(destIp, destPort)) {
			Socket socket = new Socket(destIp, destPort, InetAddress.getLocalHost(), localPort + 5);
			Connection connection = new Connection(socket);
			connections.add(connection);
			connection.start();
			
		}
		else {
			System.out.println("INSERT ERROR MESSAGE HERE");
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
			System.out.println("ERROR: The connection with ID=" + args[0] + " is closed.");
			return;
		}
		connection.out.println(Command.SEND + " " + args[1]);
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
		dropConnection(id);
		System.out.println("INSERT DROPPED CONNECTION MESSAGE HERE.");
	}

	private static boolean connectionExists(InetAddress ip, int port) {
		return connectionExists(ip.getHostAddress(), port);
	}

	private static boolean connectionExists(String ip, int port) {
		for (Connection connection : connections) {
			Socket socket = connection.socket;
			if (socket.isClosed()) {
				continue;
			}
			if (socket.getInetAddress().getHostAddress().equals(ip) && socket.getPort() == port) {
				return true;
			}
		}
		return false;
	}

	private static void dropConnection(int id) throws Exception {
		Socket socket = connections.get(id).socket;
		if (!socket.isClosed()) {
			socket.close();
		}
	}

	private static void dropAllConnections() throws Exception {
		for (int i = 0; i < connections.size(); i++) {
			dropConnection(i);
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

	private static String[] parseInput(String input, String startsWith, int argCount) {
		String[] args = new String[argCount];
		String temp = input.replace(startsWith, "");
		if (temp.charAt(0) != ' ') {
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
