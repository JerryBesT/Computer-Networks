// hahah

/*
CS640 Spring 2017
*/
import java.io.*;
import java.net.*;
import java.*;

public class Iperfer {

	public static void err(String text) {
		System.err.println(text);
		System.exit(1);
	}

	public static void main(String[] args) throws IOException {
		int arg_num = args.length;
		if (arg_num != 7 || arg_num != 3)
			err("Error: invalid arguments");

		// client mode
		if (arg_num == 7) {
			if(!args[0].equals("-c") || !args[1].equals("-h") || !args[3].equals("-p") || !args[5].equals("-t"))
				err("Error: invalid arguments");

			// assume hostname is valid
			String host = args[2];
			// assume time is valid
			int time = Integer.parseInt(args[6]);

			int port = Integer.parseInt(args[4]);
			if(port < 1024 || port > 65536)
				err("Error: port number must be in the range 1024 to 65536");
			
			client(host, port, time);
		}

		// server mode
		if(arg_num == 3) {
			if(!args[0].equals("-s"))
				err("Error: invalid arguments");

			int port = Integer.parseInt(args[2]);
			if(port < 1024 || port > 65536)
				err("Error: port number must be in the range 1024 to 65536");

			server(port);
		}
	}

	public static void client(String hostname, int port, int time) {
		Socket clientSoc = new Socket(host, portNumber);
		OutputStream out = clientSoc.getOutputStream();
		BufferedReader in = new BufferedReader (new InputStreamReader (clientSoc.getInputStream()));
		BufferedReader stdIn = new BufferedReader (new InputStreamReader (System.in));
		long end = System.currentTimeMillis() + time * 1000;

		int b_sent = 0;
		while(System.currentTimeMillis() < end) {
			byte[] data = new byte[1000];	
			out.write(data);
			b_sent += 1000;
		}

		int mb = b_sent / 1000;
		int mbps = mb * 8 / time;
		System.out.println("sent=" + mb + " KB rate=" + mbps + " Mbps");
		return;
	}

	public static void server(int port) {
		int serverPort = Integer.parseInt(args[0]);
		ServerSocket serverSoc = new ServerSocket(serverPort);

		System.out.println("Waiting for client connection");
		Socket clientSoc = serverSoc.accept();
		PrintWriter out = new PrintWriter(clientSoc.getOutputStream(), true);
		BufferedReader in = new BufferedReader( new InputStreamReader(clientSoc.getInputStream()));

		String text;
		while ((text = in.readLine()) != null){
			System.out.println("Server side receiving: " + text);
			out.println(text);
		}

	}
}
