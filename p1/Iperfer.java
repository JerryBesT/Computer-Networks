import java.io.*;
import java.net.*;
import java.util.*;

public class Iperfer {

	public static void err(String text) {
		System.err.println(text);
		System.exit(1);
	}

	public static void main(String[] args) throws IOException{
		int arg_num = args.length;
		// here we assume that the client has exactly seven arguments, server three arguments
		if (arg_num != 7 && arg_num != 3)
			err("Error: invalid arguments");

		// client mode
		if (arg_num == 7) {
			// check the sanity of the arguments
			if(!args[0].equals("-c") || !args[1].equals("-h") || !args[3].equals("-p") || !args[5].equals("-t"))
				err("Error: invalid arguments");

			// assume hostname is valid
			String host = args[2];
			// assume time is valid
			int time = Integer.parseInt(args[6]);

			int port = Integer.parseInt(args[4]);
			// assume port is between 1024 and 65535 inclusively
			if(port < 1024 || port > 65535)
				err("Error: port number must be in the range 1024 to 65536");

			// start client
			client(host, port, time);
		}

		// server mode
		if(arg_num == 3) {
			if(!args[0].equals("-s"))
				err("Error: invalid arguments");

			int port = Integer.parseInt(args[2]);
			// assume port is between 1024 and 65535 inclusively
			if(port < 1024 || port > 65535)
				err("Error: port number must be in the range 1024 to 65536");

			// start client
			server(port);
		}
	}

	public static void client(String hostname, int port, int time) throws IOException {
		Socket clientSoc = new Socket(hostname, port);

		double end = System.currentTimeMillis() + time * 1000;
		int b_sent = 0;

		// keep sending the bytes for the time interval
		while(System.currentTimeMillis() < end) {
			byte[] data = new byte[1000];
			// write to the bytes
			for(int i = 0;i < 1000;i++)
				data[i] = 0;

			// keeping sending in each chunk of 1000 bytes
			try {
				clientSoc.getOutputStream().write(data);
				b_sent += 1000;
			} catch(IOException e)  {
				continue;
			}
		}

		// format the bandwidth
		double mbps = b_sent * 8 / (time*1000);
		System.out.println("sent=" + (b_sent/1000) + " KB rate=" + String.format("%.3f", mbps / 1000.0) + " Mbps");
		clientSoc.close();
		return;
	}

	public static void server(int port) throws IOException {
		int serverPort = port;
		ServerSocket serverSoc = new ServerSocket(serverPort);

		System.out.println("Waiting for client connection");
		Socket clientSoc = serverSoc.accept();

		double start = System.currentTimeMillis();
		// since each read may not read the whole 1000 chunk, we declare a total to capture the trailing bytes
		long total_received = 0;
		int curr_received = 0;
		try{
			byte[] data = new byte[10000];
			// keep reading while the connection is alive
			while( (curr_received = clientSoc.getInputStream().read(data)) > 0) {
				total_received += curr_received;
			}

		} catch (IOException e) {
			e.printStackTrace();
		}

		// calculate the time of receving all the packages
		double time = (System.currentTimeMillis() - start) / 1000.0;
		double mbps = (total_received * 8) / (time*1000);
		System.out.println("received=" + (total_received/1000) + " KB rate=" + String.format("%.3f", mbps / 1000.0) + " Mbps");
		return;
	}
}
