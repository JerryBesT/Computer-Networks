package edu.wisc.cs.sdn.vnet.rt;

import java.util.*;
import java.nio.ByteBuffer;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.*;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{	
	/** Routing table for the router */
	private RouteTable routeTable;

	/** ARP cache for the router */
	private ArpCache arpCache;

	private TimeThread check_arp;
	private unsolicitedThread unsolicited_thread;
	public Map<Integer, LinkedList> ip_queues;
	public Map<Integer, Long> time;
	public Map<Integer, Integer> count;
	public Map<Ethernet, Iface> pair;
	public Map<RouteEntry, Long> entry_timings;
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
		this.ip_queues = new HashMap<Integer, LinkedList>();
		this.time = new HashMap<Integer, Long>();
		this.count = new HashMap<Integer, Integer>();
		this.pair = new HashMap<Ethernet, Iface>();
		this.check_arp = new TimeThread(ip_queues, time, count, pair);
		this.check_arp.start();
		this.entry_timings = new HashMap<RouteEntry, Long>();
		this.unsolicited_thread = new unsolicitedThread(this, this.entry_timings);
	}

	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable()
	{ return this.routeTable; }

	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile)
	{
		if (!routeTable.load(routeTableFile, this))
		{
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}

		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}

	public void runRIP() {
		if(this.interfaces == null)
			return;
		for(Iface iface : this.interfaces.values()) {
			if(iface == null) continue;
			int dstIp = iface.getIpAddress();
			int gwIp = 0;
			int maskIp = iface.getSubnetMask();
			routeTable.insert(dstIp, gwIp, maskIp, iface);
			RouteEntry e = new RouteEntry(dstIp, gwIp, maskIp, iface);
			synchronized(this.entry_timings) {entry_timings.put(e, System.currentTimeMillis()); }
			send_rip_packet(iface, 0, false);
		}
		this.unsolicited_thread.start();
	}

	public void send_rip_packet(Iface iface, int type, boolean is_unsolicited) {
		Ethernet ether = new Ethernet();
		IPv4 ip = new IPv4();
		UDP udp = new UDP();
		ether.setPayload(ip);
		ip.setPayload(udp);
	
		// request
		if(type == 0 || is_unsolicited) {
			ether.setEtherType(Ethernet.TYPE_IPv4);
			ether.setDestinationMACAddress("FF:FF:FF:FF:FF:FF");

			ip.setTtl((byte)64);
			ip.setProtocol(IPv4.PROTOCOL_UDP);
			ip.setDestinationAddress("224.0.0.9");
			udp.setSourcePort(UDP.RIP_PORT);
			udp.setDestinationPort(UDP.RIP_PORT);
		}

		// response
		if(type == 1) {
			ether.setEtherType(Ethernet.TYPE_IPv4);
			ether.setDestinationMACAddress(iface.getMacAddress().toBytes());

			ip.setTtl((byte)64);
			ip.setProtocol(IPv4.PROTOCOL_UDP);
			ip.setDestinationAddress(iface.getIpAddress());
			udp.setSourcePort(UDP.RIP_PORT);
			udp.setDestinationPort(UDP.RIP_PORT);
		}
		boolean check = this.sendPacket(ether, iface);
	}

	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile)
	{
		if (!arpCache.load(arpCacheFile))
		{
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}

		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));

		/********************************************************************/

		switch(etherPacket.getEtherType())
		{
			case Ethernet.TYPE_IPv4:
				this.handleIpPacket(etherPacket, inIface);
				break;
			case Ethernet.TYPE_ARP:
				System.out.println("arp packet received ***");
				this.handleARP(etherPacket, inIface);
				break;
			default:
				System.out.println("other type received");
			// Ignore all other packet types, for now
		}

		/********************************************************************/
	}

	private void handleUDP(Ethernet etherPacket, Iface inIface) {

		IPv4 ipPacket = (IPv4)etherPacket.getPayload();
		UDP udp = (UDP)ipPacket.getPayload();
		int temp = IPv4.toIPv4Address("224.0.0.9");
		
		// bad
		if(ipPacket.getDestinationAddress() != temp || ipPacket.getProtocol() != IPv4.PROTOCOL_UDP || 
			udp.getDestinationPort() != (short)520) {
			return;
		}

		// receving reponse
		if(ipPacket.getDestinationAddress() == inIface.getIpAddress()) {
			// check if the IP is in the table already
			int dstIp = inIface.getIpAddress();
			int gwIp = 0;
			int maskIp = inIface.getSubnetMask();
			RouteEntry entry = routeTable.find(dstIp, maskIp);
			if(entry == null) {
				routeTable.insert(dstIp, gwIp, maskIp, inIface);
				RouteEntry e = new RouteEntry(dstIp, gwIp, maskIp, inIface);
				synchronized(this.entry_timings) {entry_timings.put(e, System.currentTimeMillis()); }
			}
			else {
				// nothing changes
				if(entry.getDestinationAddress() == dstIp && entry.getMaskAddress() == maskIp
						&& entry.getInterface().toString().equalsIgnoreCase(inIface.toString()))
					return;
				else {
				// update time
					routeTable.update(dstIp, gwIp, maskIp, inIface);
					send_rip_packet(inIface, 1, false);
					synchronized(this.entry_timings) {entry_timings.put(entry, System.currentTimeMillis()); }
				}
			}
		}

		// receving request, sending reponse
		if(ipPacket.getDestinationAddress() == temp) 
			send_rip_packet(inIface, 1, false);
	}

	private void handleARP(Ethernet etherPacket, Iface inIface)
	{
		if (etherPacket.getEtherType() == Ethernet.TYPE_ARP) {
			ARP arpPacket = (ARP)etherPacket.getPayload();
			if(arpPacket.getOpCode() == ARP.OP_REQUEST) {
				int targetIp = ByteBuffer.wrap(arpPacket.getTargetProtocolAddress()).getInt();
				if(targetIp == inIface.getIpAddress() ) {
					Ethernet ether = new Ethernet();
					ARP arp = new ARP();
					ether.setPayload(arp);

					ether.setEtherType(Ethernet.TYPE_ARP);
					ether.setSourceMACAddress(inIface.getMacAddress().toBytes());
					ether.setDestinationMACAddress(etherPacket.getSourceMACAddress());

					arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
					arp.setProtocolType(ARP.PROTO_TYPE_IP);
					arp.setHardwareAddressLength((byte)Ethernet.DATALAYER_ADDRESS_LENGTH);
					arp.setProtocolAddressLength((byte) 4);
					arp.setOpCode(ARP.OP_REPLY);
					arp.setSenderHardwareAddress(inIface.getMacAddress().toBytes());
					arp.setSenderProtocolAddress(inIface.getIpAddress());
					arp.setTargetHardwareAddress(arpPacket.getSenderHardwareAddress());
					arp.setTargetProtocolAddress(arpPacket.getSenderProtocolAddress());
					boolean check = this.sendPacket(ether, inIface);
				}
			}
			else if(arpPacket.getOpCode() == ARP.OP_REPLY) {
				synchronized(this.ip_queues) {
					int targetIp = ByteBuffer.wrap(arpPacket.getSenderProtocolAddress()).getInt();
					LinkedList<Ethernet> currList = ip_queues.get(targetIp);

					this.arpCache.insert(MACAddress.valueOf(arpPacket.getSenderHardwareAddress()), targetIp);
					for(int i = 0 ; i < currList.size() ; i++ ) {
						Ethernet currPkt = currList.get(i);
							currPkt.setDestinationMACAddress(arpPacket.getSenderHardwareAddress());
							this.sendPacket(currPkt, inIface);
					}
					currList.clear();
					synchronized(this.count) {count.put(targetIp, 0); }
				}
			}
		}
	}

	private void handleIpPacket(Ethernet etherPacket, Iface inIface)
	{
		// Make sure it's an IP packet
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4)
		{ return; }

		// Get IP header
		IPv4 ipPacket = (IPv4)etherPacket.getPayload();
		System.out.println("Handle IP packet");

		if(ipPacket.getProtocol() == IPv4.PROTOCOL_UDP) {
			UDP udp = (UDP)ipPacket.getPayload();
			if(udp.getSourcePort() == UDP.RIP_PORT)
				handleUDP(etherPacket, inIface);
		}


		// Verify checksum
		short origCksum = ipPacket.getChecksum();
		ipPacket.resetChecksum();
		byte[] serialized = ipPacket.serialize();
		ipPacket.deserialize(serialized, 0, serialized.length);
		short calcCksum = ipPacket.getChecksum();
		if (origCksum != calcCksum)
		{ return; }

		// Check TTL
		ipPacket.setTtl((byte)(ipPacket.getTtl()-1));
		if (0 == ipPacket.getTtl())
		{
			icmp_code(etherPacket, (byte) 11, (byte) 0, inIface);
			return;
		}

		// Reset checksum now that TTL is decremented
		ipPacket.resetChecksum();

		// Check if packet is destined for one of router's interfaces
		for (Iface iface : this.interfaces.values())
		{
			if (ipPacket.getDestinationAddress() == iface.getIpAddress())
			{ 
				if(ipPacket.getProtocol() == IPv4.PROTOCOL_UDP || ipPacket.getProtocol() == IPv4.PROTOCOL_TCP)
				{	
					icmp_code(etherPacket, (byte) 3, (byte) 3, inIface);
					return;
				}
				if(ipPacket.getProtocol() == IPv4.PROTOCOL_ICMP && ((ICMP)(ipPacket.getPayload())).getIcmpType() == 8)
				{
					//echo reply
					Ethernet ether = new Ethernet();
					IPv4 ip = new IPv4();
					ICMP icmp = new ICMP();
					Data data = new Data();
					ether.setPayload(ip);
					ip.setPayload(icmp);
					icmp.setPayload(data);

					ether.setEtherType(Ethernet.TYPE_IPv4);
					ether.setSourceMACAddress(inIface.getMacAddress().toBytes());

					int dstAddr = ipPacket.getSourceAddress();
					RouteEntry bestMatch = this.routeTable.lookup(dstAddr);
					if (null == bestMatch)  { return; }
					Iface outIface = bestMatch.getInterface();
					int nextHop = bestMatch.getGatewayAddress();
					if (0 == nextHop)
					{ nextHop = dstAddr; }
					ArpEntry arpEntry = this.arpCache.lookup(nextHop);
					if (null == arpEntry)  { return; }


					ether.setDestinationMACAddress(arpEntry.getMac().toBytes());

					ip.setTtl((byte)64);
					ip.setProtocol(IPv4.PROTOCOL_ICMP);
					ip.setSourceAddress(ipPacket.getDestinationAddress());
					ip.setDestinationAddress(ipPacket.getSourceAddress());

					icmp.setIcmpType((byte)0);
					icmp.setIcmpCode((byte)0);

					int ipHeader_length = serialized.length;

					int index = 0;

					ICMP request = (ICMP)ipPacket.getPayload();
					byte[] icmp_payload = request.getPayload().serialize();
					byte[] new_data = new byte[icmp_payload.length];
					for(int i = 0 ; i < icmp_payload.length; i++) {
						new_data[index++] = icmp_payload[i];
					}

					data.setData(new_data);
					boolean check = this.sendPacket(ether, inIface);
				} else {
					return;
				}
			}
		}

		// Do route lookup and forward
		this.forwardIpPacket(etherPacket, inIface);
	}

	private void forwardIpPacket(Ethernet etherPacket, Iface inIface)
	{
		// Make sure it's an IP packet
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4)
		{ return; }
		System.out.println("Forward IP packet");

		// Get IP header
		IPv4 ipPacket = (IPv4)etherPacket.getPayload();
		int dstAddr = ipPacket.getDestinationAddress();

		// Find matching route table entry 
		RouteEntry bestMatch = this.routeTable.lookup(dstAddr);

		// If no entry matched, do nothing
		if (null == bestMatch)
		{ 
			icmp_code(etherPacket, (byte) 3, (byte) 0, inIface);
			return; }

		// Make sure we don't sent a packet back out the interface it came in
		Iface outIface = bestMatch.getInterface();
		if (outIface == inIface)
		{ return; }

		// Set source MAC address in Ethernet header
		etherPacket.setSourceMACAddress(outIface.getMacAddress().toBytes());

		// If no gateway, then nextHop is IP destination
		int nextHop = bestMatch.getGatewayAddress();
		if (0 == nextHop)
		{ nextHop = dstAddr; }

		// Set destination MAC address in Ethernet header
		ArpEntry arpEntry = this.arpCache.lookup(nextHop);
		if (null == arpEntry)
		{ 
			//System.out.println("arp entry is null");
			synchronized(this.ip_queues) {
				if(!ip_queues.containsKey(nextHop)) {
						LinkedList<Ethernet> list = new LinkedList<Ethernet>();
						list.add(etherPacket);
						
						ip_queues.put(nextHop, list);
						synchronized(this.count) {count.put(nextHop, 0); }
						synchronized(this.pair) {pair.put(etherPacket, inIface); }
				} else {
						ip_queues.get(nextHop).add(etherPacket);
						synchronized(this.pair) {pair.put(etherPacket, inIface); }
				}
			}

		} else {
			etherPacket.setDestinationMACAddress(arpEntry.getMac().toBytes());
			this.sendPacket(etherPacket, outIface);
		}
	}

	public void icmp_code(Ethernet etherPacket, byte type, byte code , Iface inIface) {
		IPv4 ipPacket = (IPv4)etherPacket.getPayload();
		byte[] serialized = ipPacket.serialize();
		Ethernet ether = new Ethernet();
		IPv4 ip = new IPv4();
		ICMP icmp = new ICMP();
		Data data = new Data();
		ether.setPayload(ip);
		ip.setPayload(icmp);
		icmp.setPayload(data);


		ether.setEtherType(Ethernet.TYPE_IPv4);
		ether.setSourceMACAddress(inIface.getMacAddress().toBytes());

		int dstAddr = ipPacket.getSourceAddress();
		RouteEntry bestMatch = this.routeTable.lookup(dstAddr);
		if (null == bestMatch)  { return; }
		Iface outIface = bestMatch.getInterface();
		int nextHop = bestMatch.getGatewayAddress();
		if (0 == nextHop)
		{ nextHop = dstAddr; }
		ArpEntry arpEntry = this.arpCache.lookup(nextHop);
		if (null == arpEntry)  { return; }


		ether.setDestinationMACAddress(arpEntry.getMac().toBytes());

		ip.setTtl((byte)64);
		ip.setProtocol(IPv4.PROTOCOL_ICMP);
		ip.setSourceAddress(inIface.getIpAddress());
		ip.setDestinationAddress(ipPacket.getSourceAddress());

		icmp.setIcmpType((byte)type);
		icmp.setIcmpCode((byte)code);

		int ipHeader_length = serialized.length;

		byte[] new_data = new byte[ipHeader_length+12];
		int index = 0;
		for(int i = 0 ; i < 4 ; i++) {
			new_data[index++] = (byte)0;
		}
		for(int i = 0 ; i < ipHeader_length ; i ++) {
			new_data[index++] = serialized[i];
		}

		byte[] following_byte = (byte[])ipPacket.getPayload().serialize();
		for(int i = 0 ; i < 8 ; i++) {
			new_data[index++] = following_byte[i];
		}

		data.setData(new_data);
		boolean result = this.sendPacket(ether, inIface);
		return;
	}
		
		public void send_arp(int nextHop, Iface inIface) {
			Ethernet ether = new Ethernet();
			ARP arp = new ARP();
			ether.setPayload(arp);

			ether.setEtherType(Ethernet.TYPE_ARP);
			ether.setSourceMACAddress(inIface.getMacAddress().toBytes());
			ether.setDestinationMACAddress("FF:FF:FF:FF:FF:FF");

			arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
			arp.setProtocolType(ARP.PROTO_TYPE_IP);
			arp.setHardwareAddressLength((byte)Ethernet.DATALAYER_ADDRESS_LENGTH);
			arp.setProtocolAddressLength((byte) 4);
			arp.setOpCode(ARP.OP_REQUEST);
			arp.setSenderHardwareAddress(inIface.getMacAddress().toBytes());
			arp.setSenderProtocolAddress(inIface.getIpAddress());
			arp.setTargetHardwareAddress(new byte[6]);
			arp.setTargetProtocolAddress(nextHop);
			RouteEntry bestMatch = this.routeTable.lookup(nextHop);
			Iface outIface = bestMatch.getInterface();
			
			boolean	result = this.sendPacket(ether, outIface);
		}

		
		class TimeThread extends Thread
		{
			Map<Integer, LinkedList> ip_queues;
			Map<Integer, Long> time;
			Map<Integer, Integer> count;
			Map<Ethernet, Iface> pair;
			public TimeThread(Map<Integer, LinkedList> ip_queues, 
					Map<Integer, Long> time, Map<Integer, Integer> count, Map<Ethernet, Iface> pair) {
				this.ip_queues = ip_queues;
				this.time = time;
				this.count = count;
				this.pair = pair;
			}

			public void run() {
				while(true) {
					if(this.ip_queues != null) {
						synchronized(this.ip_queues) {
							synchronized(this.count) {
								Iterator queueIt = this.ip_queues.entrySet().iterator();
								while (queueIt.hasNext()) {
									Map.Entry mapElement = (Map.Entry)queueIt.next();
									Integer currip = (Integer)(mapElement.getKey());
									int currIP = currip.intValue();
									LinkedList<Ethernet> currQ = this.ip_queues.get(currIP);
									if(!currQ.isEmpty() && count.get(currIP) == 0) {
									  Ethernet currPkt = currQ.get(0);
										Iface currIface = pair.get(currPkt);
										send_arp(currIP, currIface);
										count.put(currIP, count.get(currIP)+1);
										long currTime = System.currentTimeMillis();
										time.put(currIP, currTime);
									}
									long currT = System.currentTimeMillis();
									if(!currQ.isEmpty() && (time.get(currIP) + 1000 < currT) && count.get(currIP) < 3){
										Ethernet currPkt = currQ.get(0);
										Iface currIface = pair.get(currPkt);
										send_arp(currIP, currIface);
										count.put(currIP, count.get(currIP)+1);
										long currTime = System.currentTimeMillis();
										time.put(currIP, currTime);
									}
									if(!currQ.isEmpty() && (time.get(currIP) + 1000 < currT) && count.get(currIP) == 3){
										while(!currQ.isEmpty()) {
											Ethernet currPkt = currQ.get(0);
											Iface currIface = pair.get(currPkt);
											icmp_code(currPkt, (byte) 3, (byte) 1, currIface);
											currQ.removeFirst();
											this.pair.remove(currPkt);	
										}
										count.put(currIP, 0);
									}
								}
							}
						}
					}
				}
			}
		}
		
		class unsolicitedThread extends Thread
		{
			private Router curr_r;
			private long last_time;
			Map<RouteEntry, Long> entry_timings;
			public unsolicitedThread(Router r, Map<RouteEntry, Long> timings) {
				this.curr_r = r;
				this.last_time = System.currentTimeMillis();
				this.entry_timings = timings;
			}
			public void run(){
				long curr_time = System.currentTimeMillis();
				if(curr_time - last_time > 10000) {
					Map<String, Iface> interface_list = curr_r.getInterfaces();
					for(Iface iface : interface_list.values()) {
						int dstIP = iface.getIpAddress();
						int gwIp = 0;
						int maskIp = iface.getSubnetMask();
						send_rip_packet(iface, 1, true);
					}
					last_time = curr_time;
				}

				synchronized(this.entry_timings) {
					Iterator queueIt = this.entry_timings.entrySet().iterator();
					while (queueIt.hasNext()) {
						Map.Entry mapElement = (Map.Entry)queueIt.next();
						RouteEntry e = (RouteEntry)(mapElement.getKey());
						long timing = (long)entry_timings.get(e);
						if(curr_time - timing > 30000) {
							boolean is_local = false;														

							for(Iface iface : this.curr_r.interfaces.values()) {
								if(e.getInterface().toString().equalsIgnoreCase(iface.toString())) {
									is_local = true;
								}
							}

							if(is_local == false) {
								routeTable.remove(e.getDestinationAddress(), e.getMaskAddress());
								this.entry_timings.remove(e);
							}
						}
					}
				}

			}
		}
}
