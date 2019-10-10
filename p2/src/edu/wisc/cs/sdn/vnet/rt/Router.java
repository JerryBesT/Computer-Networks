package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.MACAddress;
import java.util.*;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{	
	/** Routing table for the router */
	private RouteTable routeTable;

	/** ARP cache for the router */
	private ArpCache arpCache;

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
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
		// if IPv4, drop
		short type = etherPacket.getEtherType();
		if(type != 0x0800) {
			//drop if not IPv4
			return;
		}

		//verify checksum
		IPv4 header = (IPv4) etherPacket.getPayload();

		short given = header.getChecksum();
		header.resetChecksum();
		header.serialize();
		short compute_checksum = header.getChecksum();
		if(given != compute_checksum) {
			return;
		}

		// check TTL
		byte curr_ttl = header.getTtl();
		if(curr_ttl == 0) {
			return;
		}
		header.setTtl((byte)(curr_ttl - 1));

		header.resetChecksum();
		header.serialize();

		// check if ip is destined
		Iterator mapIt = this.interfaces.entrySet().iterator();
		// Iterate through the hashmap
		while (mapIt.hasNext()) {
			Map.Entry mapElement = (Map.Entry)mapIt.next();
			Iface outIface = (Iface)mapElement.getValue();
			// ## not necessary the incoming interface
			if(outIface.getIpAddress() == header.getDestinationAddress()) {
				return;
			}
		}

		// forwarding
		int dest_ip = inIface.getIpAddress();
		RouteEntry out_entry = this.routeTable.lookup(header.getDestinationAddress());
		
		if(out_entry == null)
		{
			return;
		}
		if(out_entry.getInterface().getMacAddress() == inIface.getMacAddress()) {
			return;
		}

		if(out_entry.getGatewayAddress() == 0)
		{
			dest_ip = out_entry.getDestinationAddress();
			ArpEntry arp = this.arpCache.lookup(header.getDestinationAddress());
			MACAddress next_mac = arp.getMac();
			etherPacket.setDestinationMACAddress(next_mac.toString());
			if(out_entry.getInterface().getMacAddress() == null)
				return;
			etherPacket.setSourceMACAddress(out_entry.getInterface().getMacAddress().toString());
			boolean sending = this.sendPacket(etherPacket, out_entry.getInterface());
		}
		else
		{
			dest_ip = out_entry.getGatewayAddress();
			// re-package the packet
			ArpEntry arp = this.arpCache.lookup(dest_ip);
			MACAddress next_mac = arp.getMac();
			etherPacket.setDestinationMACAddress(next_mac.toString());
			etherPacket.setSourceMACAddress(out_entry.getInterface().getMacAddress().toString());
			boolean sending = this.sendPacket(etherPacket, out_entry.getInterface());
		}
	}
}

