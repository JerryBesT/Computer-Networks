package edu.wisc.cs.sdn.simpledns;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.File;
import java.util.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;

import java.nio.ByteBuffer;

import edu.wisc.cs.sdn.simpledns.packet.DNS;
import edu.wisc.cs.sdn.simpledns.packet.DNSQuestion;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdataAddress;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdataName;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdataString;
import edu.wisc.cs.sdn.simpledns.packet.DNSResourceRecord;

class subnet {
	private int ip;
	private int mask;
	private String location;

	public subnet(int ip, int mask, String location) {
		this.ip = ip;
		this.mask = mask;
		this.location = location;
	}

	public int getIp() {
		return this.ip;
	}

	public int getMask() {
		return this.mask;
	}

	public String getLoc() {
		return this.location;
	}

	public boolean match(InetAddress address) {
		int maskedAddr = ByteBuffer.wrap(address.getAddress()).getInt() & this.mask;

		if ((this.ip & this.mask) == maskedAddr) {
			return true;
		}
		return false;
	}
}



public class SimpleDNS
{
	private static final int PORT = 8053;
	private static final int DNS_PORT = 53;
	private static final int SIZE = 4096;
	private static List<subnet> list = new ArrayList<subnet>();
	private static DatagramSocket socket;


	public static void main(String[] args)
	{

		File file = null;
		try{
			socket = new DatagramSocket(PORT);
		} catch (Exception e) {

		}
		InetAddress root = null;
		for (int i = 0; i < args.length; i++)
		{
			if(args[i].equals("-r")) {
				try {
					root = InetAddress.getByName(args[i + 1]);
				} catch (UnknownHostException e) {
					break;
				}
			}
			else if (args[i].equals("-e")) {
				file = new File(args[i + 1]);
			} else {
				continue;
			}

		}


		readFile(file);

		// handle packet
		try {
			DatagramPacket origPkt = new DatagramPacket(new byte[SIZE], SIZE);
			while (true) {
				System.out.println("waiting for query");

				//receive a packet
				socket.receive(origPkt);

				//parse packet
				DNS pkt = DNS.deserialize(origPkt.getData(), origPkt.getLength());

				//check validity
				if (pkt.getOpcode()!= DNS.OPCODE_STANDARD_QUERY) {
					continue;
				}
				if (pkt.getQuestions().isEmpty()) {
					continue;
				}
				DNSQuestion question = pkt.getQuestions().get(0);

				//check question type
				if(typeCheck(question.getType()) == false) {
					continue;
				}

				//create a reply
				DatagramPacket real_reply = null;

				//check if recursion
				if (pkt.isRecursionDesired()) {
					//get reply from root
					DNS root_reply = handleRecursive(question, root);

					//set up reply
					root_reply.setQuestions(pkt.getQuestions());
					root_reply.setId(pkt.getId());

					//parse and create the real reply to client
					byte[] temp = root_reply.serialize();
					real_reply = new DatagramPacket(temp, temp.length);
				}
				else {
					//send query to root
					DatagramPacket query = new DatagramPacket(origPkt.getData(), origPkt.getLength());
					query.setAddress(root);
					query.setPort(DNS_PORT);

					//create the real reply
					real_reply = new DatagramPacket(new byte[SIZE], SIZE);

					socket.send(query);

					//wait for reply from root
					socket.receive(real_reply);
				}

				real_reply.setPort(origPkt.getPort());
				real_reply.setAddress(origPkt.getAddress());
				socket.send(real_reply);
			}

		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	public static void readFile(File file)
	{
		try{
			//scan the file line by line
			Scanner scnr = new Scanner(file);
			while(scnr.hasNextLine()) {
				String line = scnr.nextLine();
				String[] parts_1 = line.split(",");
				String location = parts_1[1];
				String[] parts_2 = parts_1[0].split("/");

				String[] parts_3 = parts_2[0].split("\\.");
				//I copied next several lines of code from prev hw
				int ip = 0;
				for (int i = 0; i < 4; i++)
					ip |= (Integer.parseInt(parts_3[i])) << (8*(3-i));

				int mask = (~0) << (32 - Integer.parseInt(parts_2[1]));
				
				//add to the list
				list.add(new subnet(ip, mask, location));
			}
			scnr.close();
		} catch(Exception e) {

		}
	}

	public static boolean typeCheck(short type) {
		//check if query is the type we want
		if(type != DNS.TYPE_A && type != DNS.TYPE_AAAA && type != DNS.TYPE_CNAME && type != DNS.TYPE_NS)
			return false;
		return true;
	}



	private static DNS handleRecursive(DNSQuestion query, InetAddress root) throws IOException {
		DNS reply = null;
		DatagramPacket reply_root = new DatagramPacket(new byte[SIZE], SIZE);

		// set up and send query to root
		DNS root_query_dns = new DNS();
		root_query_dns.setOpcode(DNS.OPCODE_STANDARD_QUERY);
		root_query_dns.setId((short)0x00aa);
		root_query_dns.addQuestion(query);
		root_query_dns.setQuery(true);
		root_query_dns.setRecursionAvailable(false);
		root_query_dns.setRecursionDesired(true);

		byte[] temp = root_query_dns.serialize();
		DatagramPacket query_root = new DatagramPacket(temp, temp.length);
		query_root.setAddress(root);
		query_root.setPort(DNS_PORT);
		socket.send(query_root);


		//receive the reply from root
		socket.receive(reply_root);
		reply = DNS.deserialize(reply_root.getData(), reply_root.getLength());

		List <DNSResourceRecord> answer_list = new ArrayList<DNSResourceRecord>();
		List <DNSResourceRecord> authority_list = new ArrayList<DNSResourceRecord>();
		List <DNSResourceRecord> additional_list = new ArrayList<DNSResourceRecord>();

		//keep running
		while (true) {

			//if we did not get answer
			if(reply.getAnswers().isEmpty()){

				authority_list = reply.getAuthorities();
				additional_list = reply.getAdditional();
				//if no authority
				if (authority_list.isEmpty())
					break;

				if(typeCheck(authority_list.get(0).getType()) == false)
					break;

				for (DNSResourceRecord r : authority_list){
					//send another query
					if(r.getType() != DNS.TYPE_NS) continue;
					DNSRdataName n = (DNSRdataName)r.getData();
					if(additional_list.isEmpty() == false) {
						for (DNSResourceRecord r_1 : additional_list){
							if ( (r_1.getType() == DNS.TYPE_A) && (n.getName().contentEquals(r_1.getName()))){
								query_root.setAddress(((DNSRdataAddress)r_1.getData()).getAddress());
								socket.send(query_root);
								socket.receive(reply_root);
								reply = DNS.deserialize(reply_root.getData(), reply_root.getLength());
							}
						}
					}
					//if no additionals, we have reached the last one
					else {
						query_root.setAddress(InetAddress.getByName(n.getName()));
						socket.send(query_root);
						socket.receive(reply_root);
						reply = DNS.deserialize(reply_root.getData(), reply_root.getLength());
					}
				}
			}

			//if we already get answer
			else {

				for (DNSResourceRecord r : reply.getAnswers()){
					//add each answer to list
					answer_list.add(r);
					if (r.getType() == DNS.TYPE_CNAME && (query.getType() == DNS.TYPE_A || query.getType() == DNS.TYPE_AAAA)){
						boolean exist = false;
						for (DNSResourceRecord r_1 : reply.getAnswers()){
							String data = ((DNSRdataName)r.getData()).getName();
							if (r_1.getName().equals(data))
								exist = true;
						}
						if(exist) continue;
						DNSQuestion new_query = new DNSQuestion(((DNSRdataName)r.getData()).getName(), query.getType());
						DNS response = handleRecursive(new_query, root);
						authority_list = response.getAuthorities();
						additional_list = response.getAdditional();
						for(DNSResourceRecord single_response :response.getAnswers()){
							answer_list.add(single_response);
						}
					}
				}
				break;
			}
		}

		//check for ec2 now
		if(query.getType() == DNS.TYPE_A) {
			ArrayList<DNSResourceRecord> ec2_list = new ArrayList<DNSResourceRecord>();
			for(DNSResourceRecord record : answer_list) {
				if(record.getType() == DNS.TYPE_A) {
					DNSRdataAddress recordData = (DNSRdataAddress)(record.getData());

					//find match; if found, add to our answer
					for(subnet entry: list) {
						if(entry.match(recordData.getAddress()) == true) {
							DNSRdataString TXT = new DNSRdataString(entry.getLoc() + "-" + recordData.getAddress().getHostAddress());
							DNSResourceRecord newRecord = new DNSResourceRecord(record.getName(), (short)16, TXT);
							ec2_list.add(newRecord);
							break;
						}
					}

				}
			}
			boolean exist = false;
			//add ec2 to answer section as well
			for(DNSResourceRecord r : ec2_list){
				exist = false;
				for(DNSResourceRecord r_1 : answer_list) {
					if(r.toString().equals(r_1.toString())) {
						exist = true;
						break;
					}
				}
				if (!exist) answer_list.add(r);
			}

		}

		reply.setAnswers(answer_list);
		reply.setAuthorities(authority_list);
		reply.setAdditional(additional_list);

		return reply;
	}
}

