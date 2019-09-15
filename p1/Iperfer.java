/*
	 CS640 Spring 2017
 */
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
		if (arg_num != 7 && arg_num != 3)
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
			if(port < 1024 || port > 65535)
				err("Error: port number must be in the range 1024 to 65536");

			client(host, port, time);
		}

		// server mode
		if(arg_num == 3) {
			if(!args[0].equals("-s"))
				err("Error: invalid arguments");

			int port = Integer.parseInt(args[2]);
			if(port < 1024 || port > 65535)
				err("Error: port number must be in the range 1024 to 65536");

			server(port);
		}
	}

	public static void client(String hostname, int port, int time) throws IOException {
		Socket clientSoc = new Socket(hostname, port);

		ObjectOutputStream outStream = new ObjectOutputStream(clientSoc.getOutputStream());

		long end = System.currentTimeMillis() + time * 1000;

		long b_sent = 0;

		while(System.currentTimeMillis() < end) {  
			byte[] data = new byte[1000];
			outStream.writeObject(data);
			b_sent += 1000;
		}

		long mb = b_sent / 1000;
		// double may not hold long time period
		double mbps = mb * 8 / time;
		System.out.println("sent=" + mb + " KB rate=" + String.format("%.3f", mbps / 1000.0) + " Mbps");
		return;
	}

	public static void server(int port) throws IOException {
		int serverPort = port;
		ServerSocket serverSoc = new ServerSocket(serverPort);

		System.out.println("Waiting for client connection");
		Socket clientSoc = serverSoc.accept();
		ObjectInputStream in = new ObjectInputStream(clientSoc.getInputStream());

		long start = System.currentTimeMillis();
		long b_received = 0;

		try{
			byte[] data = new byte[1000];
			while(true) {
				data = (byte[]) in.readObject();
				b_received += 1000;
			}

		} catch (IOException e) {

		} catch (ClassNotFoundException cn) {
			cn.printStackTrace();
		}

		long mb = b_received / 1000;
		// double may not hold long time period
		double time = (System.currentTimeMillis() - start) / 1000.0;
		double mbps = mb * 8 / time;
		System.out.println("received=" + mb + " KB rate=" + String.format("%.3f", mbps / 1000.0) + " Mbps");
		return;
	}
}
